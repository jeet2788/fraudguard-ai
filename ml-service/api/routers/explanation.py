"""
ml-service/api/routers/explanation.py
POST /explain — SHAP → plain-English analyst explanation via AWS Bedrock (Claude 3 Sonnet).
Step 04 in the architecture diagram.
"""
import json
import logging

import boto3
from botocore.exceptions import ClientError
from fastapi import APIRouter

from api.schemas import ExplanationRequest, ExplanationResponse
from config import get_settings

log    = logging.getLogger(__name__)
router = APIRouter()
CFG    = get_settings()


def _bedrock_client():
    return boto3.client(
        "bedrock-runtime",
        region_name=CFG.aws_region,
        aws_access_key_id=CFG.aws_access_key_id or None,
        aws_secret_access_key=CFG.aws_secret_access_key or None,
    )


def _top_shap(shap: dict[str, float], n: int = 5) -> list[tuple[str, float]]:
    return sorted(shap.items(), key=lambda x: abs(x[1]), reverse=True)[:n]


def _build_prompt(req: ExplanationRequest) -> str:
    lines = "\n".join(
        f"  - {name}: {'raises' if v > 0 else 'lowers'} fraud probability "
        f"(contribution = {v:+.4f})"
        for name, v in _top_shap(req.shap_values)
    )
    return f"""You are a fraud operations analyst. A machine learning model just scored a payment transaction.

Score   : {req.fraud_score:.4f}  (0 = clean, 1 = fraud; threshold 0.72 = block)
Decision: {req.decision.value}

Top risk factors (SHAP attributions):
{lines}

Write a concise 2-3 sentence plain-English explanation for a fraud analyst:
- Name the 2–3 biggest risk factors using natural language (e.g. "unusually high spend", not "amount_zscore")
- Explain what they mean in business terms
- Be specific — refer to "this transaction", not "the model"
- Do not start with "I" or "The model"

Explanation:"""


@router.post("/explain", response_model=ExplanationResponse)
def explain(req: ExplanationRequest) -> ExplanationResponse:
    """
    Calls AWS Bedrock (Claude 3 Sonnet) to produce an analyst-ready NL explanation
    from SHAP values. Falls back to a rule-based explanation if Bedrock is unavailable.
    """
    try:
        client = _bedrock_client()
        body   = json.dumps({
            "anthropic_version": "bedrock-2023-05-31",
            "max_tokens": 300,
            "messages": [{"role": "user", "content": _build_prompt(req)}]
        })
        resp        = client.invoke_model(modelId=CFG.bedrock_model_id,
                                          contentType="application/json",
                                          accept="application/json", body=body)
        result      = json.loads(resp["body"].read())
        explanation = result["content"][0]["text"].strip()
        log.info("Bedrock explanation ok txn=%s", req.transaction_id)

    except ClientError as e:
        log.error("Bedrock error txn=%s: %s", req.transaction_id, e)
        explanation = _fallback(req)
    except Exception as e:
        log.error("Explanation error txn=%s: %s", req.transaction_id, e)
        explanation = _fallback(req)

    return ExplanationResponse(
        transaction_id=req.transaction_id,
        explanation=explanation,
        decision=req.decision,
    )


def _fallback(req: ExplanationRequest) -> str:
    top = _top_shap(req.shap_values, n=2)
    factors = " and ".join(
        f"{'elevated' if v > 0 else 'unusually low'} {n.replace('_', ' ')}"
        for n, v in top
    )
    return (
        f"This transaction received a fraud score of {req.fraud_score:.2f}, "
        f"resulting in a {req.decision.value} decision. "
        f"Key risk factors: {factors}."
    )
