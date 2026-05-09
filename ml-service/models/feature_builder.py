"""
ml-service/models/feature_builder.py
Converts a ScoringRequest into a numpy array for XGBoost.
Feature order MUST stay in sync with training/train.py.
"""
import numpy as np
from api.schemas import ScoringRequest

CHANNEL_CATEGORIES = ["MOBILE_APP", "POS_TERMINAL", "WEB_CHECKOUT", "PARTNER_API"]

FEATURE_NAMES: list[str] = [
    "amount",
    "velocity",
    "geo_distance",
    "merchant_risk",
    "time_of_day",
    "day_of_week",
    "amount_zscore",
    "user_avg_amount",
    "user_txn_count_24h",
    "channel_MOBILE_APP",
    "channel_POS_TERMINAL",
    "channel_WEB_CHECKOUT",
    "channel_PARTNER_API",
    "has_device_fingerprint",
]


def build_feature_array(req: ScoringRequest) -> np.ndarray:
    """Return shape (1, len(FEATURE_NAMES)) float32 array."""
    channel_ohe = [1.0 if req.channel.value == c else 0.0 for c in CHANNEL_CATEGORIES]

    row = [
        req.amount,
        req.velocity,
        req.geo_distance,
        req.merchant_risk,
        req.time_of_day,
        float(req.day_of_week),
        req.amount_zscore,
        req.user_avg_amount,
        float(req.user_txn_count_24h),
        *channel_ohe,
        1.0 if req.device_fingerprint else 0.0,
    ]

    assert len(row) == len(FEATURE_NAMES), \
        f"Built {len(row)} features, expected {len(FEATURE_NAMES)}"

    return np.array([row], dtype=np.float32)
