"""
ml-service/models/model_store.py
Thread-safe singleton: loads champion XGBoost model + SHAP explainer from MLflow.
Supports hot-reload without restarting the service.
"""
import logging
import threading
from datetime import datetime, timezone
from typing import Optional

import mlflow
import mlflow.xgboost
import shap
import xgboost as xgb
import numpy as np

from config import get_settings

log = logging.getLogger(__name__)
CFG = get_settings()

_lock  = threading.RLock()
_state: dict = {
    "model":      None,
    "explainer":  None,
    "version":    None,
    "run_id":     None,
    "auc_roc":    None,
    "trained_at": None,
}


class ModelStore:

    # ── Load champion from MLflow ─────────────────────────────────────────────
    @classmethod
    def load_champion(cls):
        mlflow.set_tracking_uri(CFG.mlflow_tracking_uri)
        try:
            client   = mlflow.MlflowClient()
            versions = client.get_latest_versions("fraud-xgboost", stages=["Production"])
            if not versions:
                log.warning("No Production model in MLflow — loading fallback")
                cls._load_fallback()
                return

            mv    = versions[0]
            model = mlflow.xgboost.load_model(f"models:/fraud-xgboost/Production")
            explainer = shap.TreeExplainer(model)

            run   = client.get_run(mv.run_id)
            auc   = float(run.data.metrics.get("auc_roc", 0.0))

            with _lock:
                _state.update(model=model, explainer=explainer, version=mv.version,
                              run_id=mv.run_id, auc_roc=auc,
                              trained_at=str(mv.creation_timestamp))
            log.info("Champion loaded: version=%s  AUC=%.4f", mv.version, auc)

        except Exception as exc:
            log.error("MLflow load failed (%s) — using fallback", exc)
            cls._load_fallback()

    # ── Fallback: tiny trained model so service starts without MLflow ─────────
    @classmethod
    def _load_fallback(cls):
        from models.feature_builder import FEATURE_NAMES
        log.warning("Fallback model loaded — predictions are NOT meaningful!")
        model = xgb.XGBClassifier(n_estimators=10, eval_metric="logloss")
        n     = len(FEATURE_NAMES)
        X     = np.random.rand(30, n).astype(np.float32)
        y     = np.array([0]*24 + [1]*6)
        model.fit(X, y)
        explainer = shap.TreeExplainer(model)

        with _lock:
            _state.update(model=model, explainer=explainer, version="fallback-v0",
                          run_id="none", auc_roc=0.0,
                          trained_at=datetime.now(timezone.utc).isoformat())

    # ── Accessors ─────────────────────────────────────────────────────────────
    @classmethod
    def get_champion(cls) -> Optional[dict]:
        with _lock:
            return dict(_state) if _state["model"] else None

    @classmethod
    def get_model_info(cls) -> Optional[dict]:
        with _lock:
            if not _state["version"]:
                return None
            return {
                "model_version": _state["version"],
                "run_id":        _state["run_id"],
                "auc_roc":       _state["auc_roc"],
                "trained_at":    _state["trained_at"],
                "champion":      True,
                "stage":         "Production",
            }

    # ── Promote challenger ────────────────────────────────────────────────────
    @classmethod
    def promote(cls, run_id: str, version: str, auc_roc: float):
        mlflow.set_tracking_uri(CFG.mlflow_tracking_uri)
        client = mlflow.MlflowClient()

        # Archive current champion
        current = cls.get_model_info()
        if current:
            try:
                client.transition_model_version_stage(
                    name="fraud-xgboost",
                    version=current["model_version"],
                    stage="Archived"
                )
            except Exception as e:
                log.warning("Could not archive old champion: %s", e)

        client.transition_model_version_stage(
            name="fraud-xgboost", version=version, stage="Production"
        )
        cls.load_champion()
        log.info("Promoted version=%s  AUC=%.4f", version, auc_roc)
