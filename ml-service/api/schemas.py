"""ml-service/api/schemas.py"""
from __future__ import annotations
from enum import Enum
from typing import Optional
from pydantic import BaseModel, Field


class Channel(str, Enum):
    MOBILE_APP   = "MOBILE_APP"
    POS_TERMINAL = "POS_TERMINAL"
    WEB_CHECKOUT = "WEB_CHECKOUT"
    PARTNER_API  = "PARTNER_API"


class Decision(str, Enum):
    ALLOW  = "ALLOW"
    REVIEW = "REVIEW"
    BLOCK  = "BLOCK"


# ── Scoring ───────────────────────────────────────────────────────────────────

class ScoringRequest(BaseModel):
    transaction_id:     str
    user_id:            str
    merchant_id:        str
    amount:             float  = Field(..., gt=0)
    currency:           str    = Field(..., min_length=3, max_length=3)
    channel:            Channel
    device_fingerprint: Optional[str] = None

    # Engineered features from Spring Boot
    velocity:            float = Field(0.0)
    geo_distance:        float = Field(0.0)
    merchant_risk:       float = Field(0.5, ge=0.0, le=1.0)
    time_of_day:         float = Field(0.0, ge=0.0, le=1.0)
    day_of_week:         int   = Field(1,   ge=1,   le=7)
    amount_zscore:       float = Field(0.0)
    user_avg_amount:     float = Field(0.0)
    user_txn_count_24h:  int   = Field(0)


class ScoringResponse(BaseModel):
    transaction_id: str
    fraud_score:    float = Field(..., ge=0.0, le=1.0)
    shap_values:    dict[str, float]
    model_version:  str
    latency_ms:     float


# ── Explanation ───────────────────────────────────────────────────────────────

class ExplanationRequest(BaseModel):
    transaction_id: str
    fraud_score:    float
    shap_values:    dict[str, float]
    decision:       Decision


class ExplanationResponse(BaseModel):
    transaction_id: str
    explanation:    str
    decision:       Decision


# ── Drift ─────────────────────────────────────────────────────────────────────

class DriftCheckRequest(BaseModel):
    model_version:    str
    reference_window: int = Field(7, description="Reference window days")
    current_window:   int = Field(1, description="Current window days")


class DriftFeatureResult(BaseModel):
    feature:        str
    psi_score:      float
    ks_statistic:   float
    ks_p_value:     float
    drift_detected: bool


class DriftCheckResponse(BaseModel):
    model_version:   str
    overall_drift:   bool
    label_drift:     bool
    feature_results: list[DriftFeatureResult]
    recommendation:  str


# ── Model info ────────────────────────────────────────────────────────────────

class ModelInfoResponse(BaseModel):
    model_version: str
    run_id:        str
    auc_roc:       float
    trained_at:    str
    champion:      bool
    stage:         str


class PromoteRequest(BaseModel):
    run_id:                      str
    version:                     str
    auc_roc:                     float
    champion_auc_delta_threshold: float = 0.005
