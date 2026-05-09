"""
ml-service/api/routers/scoring.py
POST /score — XGBoost inference + SHAP values. Target: p99 < 15 ms.
"""
import logging
import time

import numpy as np
from fastapi import APIRouter, HTTPException

from api.schemas import ScoringRequest, ScoringResponse
from models.feature_builder import build_feature_array, FEATURE_NAMES
from models.model_store import ModelStore

log    = logging.getLogger(__name__)
router = APIRouter()


@router.post("/score", response_model=ScoringResponse)
def score(req: ScoringRequest) -> ScoringResponse:
    """
    Score a transaction with the live champion XGBoost model.
    Returns fraud probability 0–1 and per-feature SHAP attributions.
    """
    t0 = time.perf_counter()

    bundle = ModelStore.get_champion()
    if not bundle:
        raise HTTPException(status_code=503, detail="Model not ready")

    model     = bundle["model"]
    explainer = bundle["explainer"]
    version   = bundle["version"]

    # Build feature vector (1, n_features)
    X = build_feature_array(req)

    # XGBoost predict
    score_val = float(np.clip(model.predict_proba(X)[0, 1], 0.0, 1.0))

    # SHAP (TreeExplainer — O(n_leaves), very fast)
    shap_raw  = explainer.shap_values(X)[0]          # shape: (n_features,)
    shap_dict = {name: round(float(v), 6)
                 for name, v in zip(FEATURE_NAMES, shap_raw)}

    latency_ms = (time.perf_counter() - t0) * 1000

    log.info("score txn=%-36s score=%.4f latency=%.2fms model=%s",
             req.transaction_id, score_val, latency_ms, version)

    if latency_ms > 15:
        log.warning("SLA breach: txn=%s latency=%.2fms", req.transaction_id, latency_ms)

    return ScoringResponse(
        transaction_id=req.transaction_id,
        fraud_score=score_val,
        shap_values=shap_dict,
        model_version=version,
        latency_ms=round(latency_ms, 2),
    )
