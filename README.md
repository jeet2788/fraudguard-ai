# Real-time Fraud Detection Pipeline

## Architecture
```
[Mobile / POS / Web / Partner APIs]
           │
    AWS MSK — Kafka  (transactions.raw · fraud.scored · drift.signals)
           │
   ┌───────┴────────────────────────────────────┐
   │  Spring Boot Service  (port 8080)          │
   │  ├─ POST /auth/login  /auth/register       │
   │  ├─ POST /v1/transactions   (sync score)   │
   │  ├─ POST /v1/transactions/async (Kafka)    │
   │  ├─ GET  /v1/transactions/{id}             │
   │  ├─ PATCH /v1/transactions/{id}/label      │
   │  ├─ CRUD /v1/merchants                     │
   │  └─ GET  /v1/admin/stats|alerts|model      │
   └──────────────┬─────────────────────────────┘
                  │  REST  (WebClient)
   ┌──────────────▼─────────────────────────────┐
   │  Python ML Service  (port 8000)            │
   │  ├─ POST /score      XGBoost + SHAP        │
   │  ├─ POST /explain    AWS Bedrock NL        │
   │  ├─ POST /drift/check  PSI + KS-test       │
   │  ├─ GET  /model/info                       │
   │  ├─ POST /model/reload  (hot-swap)         │
   │  └─ POST /model/promote (A/B gate)         │
   └───────────────────────────────────────────-┘
           │
   ┌───────┴──────────────────────────────┐
   │  Retraining Pipeline (Airflow DAG)   │
   │  02:00 UTC: drift → train → promote  │
   │  Optuna + MLflow + S3                │
   └──────────────────────────────────────┘
Storage: PostgreSQL · Redis (TTL 24h) · S3 · Grafana
```

## Quick Start

### 1. Start the full stack
```bash
cd infra/docker
docker-compose up -d
```

### 2. Run ML service locally (dev)
```bash
cd ml-service
cp .env.example .env          # fill in AWS keys
pip install -r requirements.txt
uvicorn api.main:app --reload --port 8000
```

### 3. Run Spring Boot locally (dev)
```bash
cd spring-boot-service
mvn spring-boot:run
```

## API Reference

### Auth
```bash
# Register
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"analyst1","password":"pass123","email":"a@co.com"}'

# Login
curl -X POST http://localhost:8080/api/auth/login \
  -d '{"username":"analyst1","password":"pass123"}'
# → returns {"token":"eyJ..."}
```

### Score a Transaction (sync)
```bash
curl -X POST http://localhost:8080/api/v1/transactions \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "transactionId":    "txn-001",
    "userId":           "user-123",
    "merchantId":       "merchant-456",
    "amount":           2500.00,
    "currency":         "USD",
    "channel":          "WEB_CHECKOUT",
    "ipAddress":        "203.0.113.5",
    "deviceFingerprint":"fp-xyz",
    "timestamp":        "2024-06-01T10:30:00Z"
  }'
```
Response:
```json
{
  "transactionId": "txn-001",
  "fraudScore": 0.8341,
  "decision": "BLOCK",
  "nlExplanation": "This transaction shows signs of account takeover — the purchase amount is 4x the user's typical spend and was made from an unrecognised device in a new country.",
  "shapValues": {"amount_zscore": 0.42, "velocity": 0.31, ...},
  "modelVersion": "3",
  "processedAt": "2024-06-01T10:30:00.123Z"
}
```

### Admin Stats
```bash
curl http://localhost:8080/api/v1/admin/stats \
  -H "Authorization: Bearer <admin-token>"
```

### Trigger Drift Check
```bash
curl -X POST http://localhost:8000/drift/check \
  -d '{"model_version":"3","reference_window":7,"current_window":1}'
```

## Decision Thresholds
| Score        | Decision | Action                    |
|-------------|----------|---------------------------|
| ≥ 0.72      | BLOCK    | Reject + webhook merchant  |
| 0.40 – 0.72 | REVIEW   | Queue for analyst review   |
| < 0.40      | ALLOW    | Approve + log              |

## Services
| Service        | URL                       |
|----------------|---------------------------|
| Spring Boot API | http://localhost:8080/api |
| Python ML API   | http://localhost:8000     |
| MLflow UI       | http://localhost:5000     |
| Grafana         | http://localhost:3000     |
| Prometheus      | http://localhost:9090     |
