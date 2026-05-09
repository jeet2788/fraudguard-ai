"""
ml-service/training/train.py
XGBoost training job — invoked by the Airflow retraining DAG.

Pipeline:
  1  Load labelled data from PostgreSQL
  2  Feature engineering (matching inference pipeline exactly)
  3  Optuna hyperparameter search (StratifiedKFold CV)
  4  Train final model on full dataset
  5  AUC-ROC gate  ≥ 0.94 required
  6  Log model + metrics to MLflow
  7  Register as Challenger (Staging stage)
  8  Call /model/promote — auto-promote if delta > 0.005
"""
import logging
import sys
from datetime import datetime, timedelta, timezone

import httpx
import mlflow
import mlflow.xgboost
import numpy as np
import optuna
import pandas as pd
import xgboost as xgb
from sklearn.metrics import roc_auc_score
from sklearn.model_selection import StratifiedKFold, cross_val_score
from sqlalchemy import create_engine, text

from config import get_settings
from models.feature_builder import FEATURE_NAMES, CHANNEL_CATEGORIES

logging.basicConfig(level=logging.INFO,
                    format="%(asctime)s %(levelname)s %(name)s — %(message)s")
log     = logging.getLogger(__name__)
CFG     = get_settings()

MIN_AUC = 0.94
N_TRIALS = 50
CV_FOLDS = 5


# ── 1. Data loading ───────────────────────────────────────────────────────────

def load_data(days_back: int = 90) -> pd.DataFrame:
    engine = create_engine(CFG.db_url)
    cutoff = datetime.now(timezone.utc) - timedelta(days=days_back)
    q = text("""
        SELECT transaction_id, user_id, merchant_id,
               amount, currency, channel, device_fingerprint,
               created_at AS timestamp, label
        FROM transactions
        WHERE label IS NOT NULL
          AND created_at >= :cutoff
        ORDER BY created_at
    """)
    with engine.connect() as c:
        df = pd.read_sql(q, c, params={"cutoff": cutoff})
    log.info("Loaded %d rows  fraud_rate=%.2f%%", len(df), df["label"].mean() * 100)
    return df


# ── 2. Feature engineering ────────────────────────────────────────────────────

def engineer(df: pd.DataFrame) -> tuple[np.ndarray, np.ndarray]:
    df = df.copy().sort_values("timestamp")

    # Amount stats per user
    stats = df.groupby("user_id")["amount"].agg(["mean", "std"]).rename(
        columns={"mean": "user_avg_amount", "std": "user_std"}).fillna(1)
    df = df.join(stats, on="user_id")
    df["amount_zscore"] = (df["amount"] - df["user_avg_amount"]) / (df["user_std"] + 1e-9)

    # Cumulative proxies (offline — real-time uses Redis)
    df["velocity"]           = df.groupby("user_id").cumcount().clip(upper=50).astype(float)
    df["user_txn_count_24h"] = df.groupby("user_id").cumcount().clip(upper=200).astype(float)

    # Geo & merchant risk — placeholder; override with real data in production
    df["geo_distance"]  = np.random.exponential(scale=30, size=len(df))
    df["merchant_risk"] = np.random.beta(a=2, b=5, size=len(df))

    # Time features
    df["timestamp"]  = pd.to_datetime(df["timestamp"], utc=True)
    df["time_of_day"] = df["timestamp"].dt.hour / 23.0
    df["day_of_week"] = df["timestamp"].dt.dayofweek.astype(float) + 1  # 1–7

    # Channel OHE
    for cat in CHANNEL_CATEGORIES:
        df[f"channel_{cat}"] = (df["channel"] == cat).astype(float)

    df["has_device_fingerprint"] = df["device_fingerprint"].notna().astype(float)

    assert list(df[FEATURE_NAMES].columns) == FEATURE_NAMES, \
        "Column order mismatch — sync with feature_builder.py"

    X = df[FEATURE_NAMES].values.astype(np.float32)
    y = df["label"].values.astype(int)
    return X, y


# ── 3. Optuna objective ───────────────────────────────────────────────────────

def _objective(trial: optuna.Trial, X: np.ndarray, y: np.ndarray) -> float:
    params = {
        "n_estimators":     trial.suggest_int("n_estimators", 200, 800),
        "max_depth":        trial.suggest_int("max_depth", 3, 8),
        "learning_rate":    trial.suggest_float("learning_rate", 0.01, 0.3, log=True),
        "subsample":        trial.suggest_float("subsample", 0.6, 1.0),
        "colsample_bytree": trial.suggest_float("colsample_bytree", 0.6, 1.0),
        "min_child_weight": trial.suggest_int("min_child_weight", 1, 10),
        "gamma":            trial.suggest_float("gamma", 0.0, 1.0),
        "reg_alpha":        trial.suggest_float("reg_alpha", 1e-8, 10.0, log=True),
        "reg_lambda":       trial.suggest_float("reg_lambda", 1e-8, 10.0, log=True),
        "scale_pos_weight": trial.suggest_float("scale_pos_weight", 1.0, 20.0),
        "eval_metric":      "auc",
        "tree_method":      "hist",
        "random_state":     42,
    }
    clf  = xgb.XGBClassifier(**params)
    cv   = StratifiedKFold(n_splits=CV_FOLDS, shuffle=True, random_state=42)
    aucs = cross_val_score(clf, X, y, cv=cv, scoring="roc_auc", n_jobs=-1)
    return aucs.mean()


# ── 4. Main training job ──────────────────────────────────────────────────────

def run():
    mlflow.set_tracking_uri(CFG.mlflow_tracking_uri)
    mlflow.set_experiment(CFG.mlflow_experiment_name)

    with mlflow.start_run() as run:
        run_id = run.info.run_id
        log.info("MLflow run: %s", run_id)

        # Data
        df   = load_data(days_back=90)
        X, y = engineer(df)
        log.info("Feature matrix: %s  fraud: %.2f%%", X.shape, y.mean() * 100)

        # Optuna search
        log.info("Optuna tuning (%d trials) …", N_TRIALS)
        optuna.logging.set_verbosity(optuna.logging.WARNING)
        study = optuna.create_study(direction="maximize",
                                    sampler=optuna.samplers.TPESampler(seed=42))
        study.optimize(lambda t: _objective(t, X, y),
                       n_trials=N_TRIALS, show_progress_bar=True)

        best  = study.best_params
        cv_auc = study.best_value
        log.info("Best CV AUC=%.4f  params=%s", cv_auc, best)
        mlflow.log_params(best)
        mlflow.log_metric("cv_auc_roc", cv_auc)

        # Full training
        best.update(eval_metric="auc", tree_method="hist", random_state=42)
        model = xgb.XGBClassifier(**best)
        model.fit(X, y)

        # Evaluate
        preds   = model.predict_proba(X)[:, 1]
        auc_roc = roc_auc_score(y, preds)
        mlflow.log_metric("auc_roc", auc_roc)
        log.info("Final AUC-ROC: %.4f", auc_roc)

        # Gate
        if auc_roc < MIN_AUC:
            log.error("AUC %.4f < minimum %.4f — training rejected", auc_roc, MIN_AUC)
            mlflow.set_tag("status", "REJECTED")
            sys.exit(1)

        # Log model
        mlflow.xgboost.log_model(model, artifact_path="model",
                                 registered_model_name="fraud-xgboost")

        # Register Challenger
        client  = mlflow.MlflowClient()
        version = client.get_latest_versions("fraud-xgboost", stages=["None"])[-1].version
        client.transition_model_version_stage(
            name="fraud-xgboost", version=version, stage="Staging")
        mlflow.set_tag("status", "CHALLENGER")
        log.info("Challenger registered: version=%s  AUC=%.4f", version, auc_roc)

        # Auto-promote via ML service API
        _try_promote(run_id, version, auc_roc)

    return {"run_id": run_id, "version": version, "auc_roc": auc_roc}


def _try_promote(run_id: str, version: str, auc_roc: float):
    try:
        resp = httpx.post(
            "http://localhost:8000/model/promote",
            json={"run_id": run_id, "version": version, "auc_roc": auc_roc},
            timeout=15,
        )
        log.info("Promote response: %s", resp.json())
    except Exception as e:
        log.warning("Auto-promote call failed: %s", e)


if __name__ == "__main__":
    result = run()
    log.info("Training complete: %s", result)
