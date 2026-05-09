"""
ml-service/api/routers/model_registry.py
GET  /model/info    — champion model metadata
POST /model/reload  — hot-reload from MLflow (zero downtime)
POST /model/promote — auto-promote challenger if AUC delta >= threshold
"""
import logging
from fastapi import APIRouter, HTTPException
from api.schemas import ModelInfoResponse, PromoteRequest
from models.model_store import ModelStore

log    = logging.getLogger(__name__)
router = APIRouter()


@router.get("/info", response_model=ModelInfoResponse)
def info():
    data = ModelStore.get_model_info()
    if not data:
        raise HTTPException(503, "No model loaded")
    return data


@router.post("/reload")
def reload():
    """Hot-reload champion model without restarting the service."""
    try:
        ModelStore.load_champion()
        return {"status": "reloaded", "model": ModelStore.get_model_info()}
    except Exception as e:
        log.error("Reload failed: %s", e)
        raise HTTPException(500, str(e))


@router.post("/promote")
def promote(req: PromoteRequest):
    """
    Shadow A/B gate: promote challenger only if AUC improvement >= threshold.
    Called by the Airflow training DAG after cross-validation.
    """
    champion = ModelStore.get_model_info()

    if champion:
        delta = req.auc_roc - champion["auc_roc"]
        if delta < -req.champion_auc_delta_threshold:
            raise HTTPException(409,
                f"Challenger AUC {req.auc_roc:.4f} is worse than champion "
                f"{champion['auc_roc']:.4f} by {abs(delta):.4f}. Rollback.")

    try:
        ModelStore.promote(run_id=req.run_id, version=req.version,
                           auc_roc=req.auc_roc)
        log.info("Promoted version=%s AUC=%.4f", req.version, req.auc_roc)
        return {
            "status":           "promoted",
            "new_version":      req.version,
            "new_auc":          req.auc_roc,
            "previous_version": champion["model_version"] if champion else None,
        }
    except Exception as e:
        log.error("Promotion error: %s", e)
        raise HTTPException(500, str(e))
