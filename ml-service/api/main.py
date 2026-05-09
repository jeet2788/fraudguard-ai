"""
ml-service/api/main.py
FastAPI entry point — scoring · explanation · drift · model registry
"""
import logging
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from prometheus_client import Counter, Histogram, generate_latest, CONTENT_TYPE_LATEST
from starlette.responses import Response

from api.routers import scoring, explanation, drift, model_registry
from models.model_store import ModelStore

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s — %(message)s")
log = logging.getLogger(__name__)

# ── Prometheus ────────────────────────────────────────────────────────────────
SCORE_LATENCY = Histogram(
    "ml_scoring_latency_ms", "XGBoost scoring latency (ms)",
    buckets=[1, 2, 5, 10, 15, 25, 50, 100, 250]
)
SCORE_COUNT = Counter("ml_score_requests_total", "Total scoring calls", ["decision"])


@asynccontextmanager
async def lifespan(app: FastAPI):
    log.info("Loading champion model …")
    ModelStore.load_champion()
    log.info("ML service ready ✓")
    yield
    log.info("ML service shutting down")


app = FastAPI(
    title="Fraud ML Service",
    description="XGBoost scoring · AWS Bedrock · Drift detection · MLflow",
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(CORSMiddleware, allow_origins=["*"],
                   allow_methods=["*"], allow_headers=["*"])


@app.middleware("http")
async def latency_header(request: Request, call_next):
    t0 = time.perf_counter()
    response = await call_next(request)
    ms = (time.perf_counter() - t0) * 1000
    response.headers["X-Latency-Ms"] = f"{ms:.2f}"
    if request.url.path == "/score":
        SCORE_LATENCY.observe(ms)
    return response


app.include_router(scoring.router,        tags=["Scoring"])
app.include_router(explanation.router,    tags=["Explanation"])
app.include_router(drift.router,          prefix="/drift",  tags=["Drift"])
app.include_router(model_registry.router, prefix="/model",  tags=["Model Registry"])


@app.get("/health")
def health():
    return {"status": "ok", "model": ModelStore.get_model_info()}


@app.get("/metrics")
def metrics():
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)
