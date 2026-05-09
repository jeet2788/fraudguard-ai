"""
ml-service/api/routers/drift.py
POST /drift/check — PSI + KS-test drift detection (Evidently AI + scipy).
Called by Airflow nightly at 02:00 UTC, or on-demand.
"""
import json
import logging
import time
from datetime import datetime, timedelta, timezone

import numpy as np
import pandas as pd
from fastapi import APIRouter, HTTPException
from scipy.stats import ks_2samp
from sqlalchemy import create_engine, text

from api.schemas import (DriftCheckRequest, DriftCheckResponse,
                          DriftFeatureResult)
from config import get_settings
from models.model_store import ModelStore

log    = logging.getLogger(__name__)
router = APIRouter()
CFG    = get_settings()

NUMERIC_FEATURES = [
    "amount", "time_of_day",
]
# Full feature list requires the feature store; here we check
# what's available directly in the transactions table.


def _engine():
    return create_engine(CFG.db_url)


def _fetch(engine, days: int) -> pd.DataFrame:
    cutoff = datetime.now(timezone.utc) - timedelta(days=days)
    q = text("""
        SELECT amount, fraud_score,
               extract(hour FROM created_at) / 23.0 AS time_of_day,
               label
        FROM transactions
        WHERE processed_at >= :cutoff AND fraud_score IS NOT NULL
    """)
    with engine.connect() as c:
        return pd.read_sql(q, c, params={"cutoff": cutoff})


def _psi(ref: np.ndarray, cur: np.ndarray, bins: int = 10) -> float:
    edges        = np.histogram(ref, bins=bins)[1]
    r, _         = np.histogram(ref, bins=edges)
    c, _         = np.histogram(cur, bins=edges)
    r_pct        = (r + 1e-6) / (r.sum() + 1e-6)
    c_pct        = (c + 1e-6) / (c.sum() + 1e-6)
    return float(np.sum((c_pct - r_pct) * np.log(c_pct / r_pct)))


@router.post("/check", response_model=DriftCheckResponse)
def check_drift(req: DriftCheckRequest) -> DriftCheckResponse:
    """
    Compare feature distributions between reference and current windows.
    Flags drift when KS-test p-value < threshold.
    Publishes signals to Kafka if drift detected.
    """
    engine = _engine()
    try:
        ref_df = _fetch(engine, req.reference_window)
        cur_df = _fetch(engine, req.current_window)
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"DB error: {e}")

    if ref_df.empty or cur_df.empty:
        raise HTTPException(status_code=422, detail="Insufficient data in windows")

    results: list[DriftFeatureResult] = []
    overall_drift = False

    for feat in NUMERIC_FEATURES:
        if feat not in ref_df.columns:
            continue
        ref_v = ref_df[feat].dropna().values
        cur_v = cur_df[feat].dropna().values
        if len(ref_v) < 30 or len(cur_v) < 30:
            continue

        psi          = _psi(ref_v, cur_v)
        ks_stat, ks_p = ks_2samp(ref_v, cur_v)
        drifted      = ks_p < CFG.drift_psi_threshold

        if drifted:
            overall_drift = True
            log.warning("Drift: feature=%s  PSI=%.4f  KS_p=%.4f", feat, psi, ks_p)

        results.append(DriftFeatureResult(
            feature=feat,
            psi_score=round(psi, 6),
            ks_statistic=round(float(ks_stat), 6),
            ks_p_value=round(float(ks_p), 6),
            drift_detected=drifted,
        ))

    # Score distribution drift
    label_drift = False
    if "fraud_score" in ref_df.columns:
        _, lp       = ks_2samp(ref_df["fraud_score"].dropna().values,
                                cur_df["fraud_score"].dropna().values)
        label_drift = lp < CFG.drift_psi_threshold

    recommendation = (
        "No drift detected. Model is stable."
        if not overall_drift and not label_drift
        else "Retraining recommended — " +
             ", ".join(r.feature for r in results if r.drift_detected) +
             (" + score distribution shift" if label_drift else "") + "."
    )

    if overall_drift or label_drift:
        _publish(req.model_version, results)

    return DriftCheckResponse(
        model_version=req.model_version,
        overall_drift=overall_drift,
        label_drift=label_drift,
        feature_results=results,
        recommendation=recommendation,
    )


@router.get("/status")
def status():
    return {"model": ModelStore.get_model_info(),
            "hint": "POST /drift/check for latest results"}


def _publish(model_version: str, results: list[DriftFeatureResult]):
    try:
        from confluent_kafka import Producer
        p = Producer({"bootstrap.servers": CFG.kafka_bootstrap_servers})
        for r in results:
            if r.drift_detected:
                p.produce(CFG.kafka_topic_drift_signals,
                          key=model_version,
                          value=json.dumps({
                              "model_version": model_version,
                              "feature": r.feature,
                              "psi_score": r.psi_score,
                              "ks_p_value": r.ks_p_value,
                              "timestamp": int(time.time()),
                          }))
        p.flush()
    except Exception as e:
        log.error("Kafka publish failed: %s", e)
