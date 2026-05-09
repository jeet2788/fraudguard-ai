package com.fraud.service;

import com.fraud.dto.Dtos.*;
import com.fraud.model.Transaction;
import com.fraud.model.Transaction.Decision;
import com.fraud.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Decision Engine — orchestrates the full fraud pipeline:
 *
 *   01  Feature engineering   (Redis rolling windows)
 *   02  XGBoost scoring       (Python ML service)
 *   03  SHAP + Bedrock NL     (Python ML service)
 *   04  Decision              BLOCK / REVIEW / ALLOW
 *   05  Persist + Kafka event
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionEngineService {

    private final FeatureEngineeringService featureService;
    private final MlClientService           mlClient;
    private final KafkaPublisherService     kafkaPublisher;
    private final WebhookService            webhookService;
    private final TransactionRepository     txnRepo;

    @Value("${fraud.threshold.block:0.72}")   private double blockThreshold;
    @Value("${fraud.threshold.review:0.40}")  private double reviewThreshold;

    public TransactionResponse evaluate(Transaction txn) {
        long t0 = System.currentTimeMillis();

        // ── Step 01: Feature engineering ─────────────────────────────────────
        ScoringRequest scoringReq = featureService.buildScoringRequest(txn);

        // ── Step 02: XGBoost score + SHAP (Python ML service) ─────────────────
        ScoringResponse scored = mlClient.score(scoringReq);

        // ── Step 03: Decision ─────────────────────────────────────────────────
        Decision decision = resolve(scored.getFraudScore());

        // ── Step 04: Bedrock NL explanation ──────────────────────────────────
        String explanation = mlClient.explain(
                txn.getTransactionId(),
                scored.getFraudScore(),
                scored.getShapValues(),
                decision.name()
        );

        // ── Step 05: Persist ──────────────────────────────────────────────────
        txn.setFraudScore(scored.getFraudScore());
        txn.setDecision(decision);
        txn.setNlExplanation(explanation);
        txn.setModelVersion(scored.getModelVersion());
        txn.setProcessedAt(Instant.now());
        txnRepo.save(txn);

        // ── Step 05: Publish to fraud.scored ─────────────────────────────────
        kafkaPublisher.publishFraudScored(txn, scored, explanation);

        // ── Webhook to merchant ───────────────────────────────────────────────
        webhookService.notifyMerchant(txn);

        long latency = System.currentTimeMillis() - t0;
        log.info("EVALUATED txn={} score={:.4f} decision={} latency={}ms",
                txn.getTransactionId(), scored.getFraudScore(), decision, latency);

        return TransactionResponse.builder()
                .transactionId(txn.getTransactionId())
                .fraudScore(scored.getFraudScore())
                .decision(decision.name())
                .nlExplanation(explanation)
                .shapValues(scored.getShapValues())
                .modelVersion(scored.getModelVersion())
                .processedAt(txn.getProcessedAt())
                .build();
    }

    private Decision resolve(double score) {
        if (score >= blockThreshold)  return Decision.BLOCK;
        if (score >= reviewThreshold) return Decision.REVIEW;
        return Decision.ALLOW;
    }
}
