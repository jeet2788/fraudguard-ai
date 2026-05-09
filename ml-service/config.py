"""ml-service/config.py — Central settings loaded from .env"""
from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

    # App
    debug: bool = False

    # AWS
    aws_region: str = "us-east-1"
    aws_access_key_id: str = ""
    aws_secret_access_key: str = ""
    bedrock_model_id: str = "anthropic.claude-3-sonnet-20240229-v1:0"

    # Redis
    redis_host: str = "localhost"
    redis_port: int = 6379

    # DB
    db_url: str = "postgresql://fraud_user:fraud_pass@localhost:5432/fraud_db"

    # MLflow
    mlflow_tracking_uri: str = "http://localhost:5000"
    mlflow_experiment_name: str = "fraud-detection"

    # S3
    s3_bucket: str = "fraud-detection-models"

    # Kafka
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_topic_fraud_scored: str = "fraud.scored"
    kafka_topic_drift_signals: str = "drift.signals.retraining"

    # Thresholds
    fraud_block_threshold: float = 0.72
    fraud_review_threshold: float = 0.40
    drift_psi_threshold: float = 0.05


@lru_cache()
def get_settings() -> Settings:
    return Settings()
