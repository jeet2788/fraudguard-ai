package com.fraud.service;

import com.fraud.dto.Dtos.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * HTTP client that communicates with the Python ML service (FastAPI).
 *
 *   POST /score   → XGBoost fraud score + SHAP values  (p99 < 15ms target)
 *   POST /explain → AWS Bedrock NL explanation
 *   GET  /model/info → champion model metadata
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MlClientService {

    private final WebClient mlServiceWebClient;

    @Value("${ml.service.timeout-ms}")
    private long timeoutMs;

    // ── Score ─────────────────────────────────────────────────────────────────

    public ScoringResponse score(ScoringRequest req) {
        return mlServiceWebClient.post()
                .uri("/score")
                .bodyValue(req)
                .retrieve()
                .bodyToMono(ScoringResponse.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .doOnError(e -> log.error("ML /score error txn={}: {}", req.getTransactionId(), e.getMessage()))
                .onErrorReturn(buildFallbackScore(req.getTransactionId()))
                .block();
    }

    // ── Explain ───────────────────────────────────────────────────────────────

    public String explain(String txnId, double score,
                          Map<String, Double> shapValues, String decision) {
        var req = new ExplanationRequest();
        req.setTransactionId(txnId);
        req.setFraudScore(score);
        req.setShapValues(shapValues);
        req.setDecision(decision);

        return mlServiceWebClient.post()
                .uri("/explain")
                .bodyValue(req)
                .retrieve()
                .bodyToMono(ExplanationResponse.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .map(ExplanationResponse::getExplanation)
                .onErrorReturn("Explanation temporarily unavailable.")
                .block();
    }

    // ── Model info ────────────────────────────────────────────────────────────

    public Map<?, ?> getModelInfo() {
        return mlServiceWebClient.get()
                .uri("/model/info")
                .retrieve()
                .bodyToMono(Map.class)
                .onErrorReturn(Map.of("status", "unavailable"))
                .block();
    }

    // ── Fallback score when ML service is down ────────────────────────────────

    private ScoringResponse buildFallbackScore(String txnId) {
        log.warn("Using fallback score for txn={}", txnId);
        var r = new ScoringResponse();
        r.setTransactionId(txnId);
        r.setFraudScore(0.5);          // conservative → REVIEW
        r.setShapValues(Map.of());
        r.setModelVersion("fallback");
        return r;
    }
}
