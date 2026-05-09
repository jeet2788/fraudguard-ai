"""
infra/airflow/dags/fraud_retraining_dag.py
Nightly retraining pipeline — runs at 02:00 UTC.
Triggers drift check → training → promote → alert if needed.
"""
from __future__ import annotations

from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.python import PythonOperator, BranchPythonOperator
from airflow.operators.empty import EmptyOperator

import httpx
import logging

log = logging.getLogger(__name__)

ML_SERVICE_URL   = "http://ml-service:8000"
DEFAULT_MODEL_VER = "latest"

default_args = {
    "owner":            "fraud-ml-team",
    "retries":          2,
    "retry_delay":      timedelta(minutes=5),
    "email_on_failure": True,
    "email":            ["ml-alerts@yourcompany.com"],
}


# ── Task functions ────────────────────────────────────────────────────────────

def check_drift(**ctx):
    """Step 1: Run drift detection. If drift found, proceed to retrain."""
    resp = httpx.post(
        f"{ML_SERVICE_URL}/drift/check",
        json={"model_version": DEFAULT_MODEL_VER,
              "reference_window": 7, "current_window": 1},
        timeout=60,
    )
    resp.raise_for_status()
    result = resp.json()
    log.info("Drift check: overall=%s  label=%s",
             result["overall_drift"], result["label_drift"])

    # Push result for downstream tasks
    ctx["ti"].xcom_push(key="drift_result", value=result)

    return "retrain" if (result["overall_drift"] or result["label_drift"]) \
                     else "no_drift_skip"


def run_training(**ctx):
    """Step 2: Trigger XGBoost training job."""
    import subprocess, sys
    result = subprocess.run(
        [sys.executable, "/opt/ml-service/training/train.py"],
        capture_output=True, text=True
    )
    if result.returncode != 0:
        raise RuntimeError(f"Training failed:\n{result.stderr}")
    log.info("Training stdout:\n%s", result.stdout)


def reload_model(**ctx):
    """Step 3: Hot-reload champion model in serving layer."""
    resp = httpx.post(f"{ML_SERVICE_URL}/model/reload", timeout=30)
    resp.raise_for_status()
    log.info("Model reloaded: %s", resp.json())


# ── DAG definition ────────────────────────────────────────────────────────────

with DAG(
    dag_id="fraud_retraining_pipeline",
    default_args=default_args,
    description="Nightly fraud model drift check + retraining",
    schedule="0 2 * * *",    # 02:00 UTC every night
    start_date=datetime(2024, 1, 1),
    catchup=False,
    tags=["fraud", "ml", "retraining"],
) as dag:

    drift_check = BranchPythonOperator(
        task_id="drift_check",
        python_callable=check_drift,
    )

    retrain = PythonOperator(
        task_id="retrain",
        python_callable=run_training,
    )

    reload = PythonOperator(
        task_id="reload_model",
        python_callable=reload_model,
    )

    no_drift = EmptyOperator(task_id="no_drift_skip")
    done     = EmptyOperator(task_id="done", trigger_rule="none_failed_min_one_success")

    drift_check >> [retrain, no_drift]
    retrain     >> reload >> done
    no_drift    >> done
