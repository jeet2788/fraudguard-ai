"""ml-service/tests/test_scoring.py"""
import numpy as np
import pytest
from fastapi.testclient import TestClient
from unittest.mock import patch, MagicMock

from api.main import app
from api.schemas import ScoringRequest, Channel

client = TestClient(app)


def _mock_bundle():
    import xgboost as xgb, shap
    model = xgb.XGBClassifier(n_estimators=5, eval_metric="logloss")
    from models.feature_builder import FEATURE_NAMES
    X = np.random.rand(20, len(FEATURE_NAMES)).astype(np.float32)
    y = np.array([0]*16 + [1]*4)
    model.fit(X, y)
    return {"model": model, "explainer": shap.TreeExplainer(model),
            "version": "test-v1", "run_id": "run-0", "auc_roc": 0.95}


@pytest.fixture(autouse=True)
def mock_model():
    with patch("models.model_store.ModelStore.get_champion", return_value=_mock_bundle()), \
         patch("models.model_store.ModelStore.get_model_info", return_value={
             "model_version": "test-v1", "run_id": "run-0",
             "auc_roc": 0.95, "trained_at": "2024-01-01",
             "champion": True, "stage": "Production"
         }):
        yield


def test_score_returns_valid_response():
    payload = {
        "transaction_id": "txn-test-001",
        "user_id": "user-1",
        "merchant_id": "merch-1",
        "amount": 500.0,
        "currency": "USD",
        "channel": "WEB_CHECKOUT",
        "velocity": 3.0,
        "geo_distance": 10.0,
        "merchant_risk": 0.3,
        "time_of_day": 0.5,
        "day_of_week": 3,
        "amount_zscore": 1.2,
        "user_avg_amount": 200.0,
        "user_txn_count_24h": 5,
    }
    resp = client.post("/score", json=payload)
    assert resp.status_code == 200
    data = resp.json()
    assert data["transaction_id"] == "txn-test-001"
    assert 0.0 <= data["fraud_score"] <= 1.0
    assert "shap_values" in data
    assert data["model_version"] == "test-v1"


def test_score_missing_required_fields():
    resp = client.post("/score", json={"transaction_id": "x"})
    assert resp.status_code == 422


def test_health():
    resp = client.get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"
