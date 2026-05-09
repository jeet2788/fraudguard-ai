package com.fraud.service;

import com.fraud.dto.Dtos.ScoringResponse;
import com.fraud.model.Transaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaPublisherService {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Value("${fraud.kafka.topic.fraud-scored}")   private String fraudScoredTopic;
    @Value("${fraud.kafka.topic.drift-signals}")  private String driftSignalsTopic;

    public void publishFraudScored(Transaction txn, ScoringResponse scored, String explanation) {
        Map<String, Object> event = new HashMap<>();
        event.put("transaction_id",  txn.getTransactionId());
        event.put("user_id",         txn.getUserId());
        event.put("merchant_id",     txn.getMerchantId());
        event.put("amount",          txn.getAmount());
        event.put("fraud_score",     scored.getFraudScore());
        event.put("decision",        txn.getDecision().name());
        event.put("shap_values",     scored.getShapValues());
        event.put("nl_explanation",  explanation);
        event.put("model_version",   scored.getModelVersion());
        event.put("processed_at",    Instant.now().toString());

        send(fraudScoredTopic, txn.getTransactionId(), event);
    }

    public void publishDriftSignal(String modelVersion, String feature,
                                   double psiScore, boolean driftDetected) {
        Map<String, Object> signal = new HashMap<>();
        signal.put("model_version",  modelVersion);
        signal.put("feature",        feature);
        signal.put("psi_score",      psiScore);
        signal.put("drift_detected", driftDetected);
        signal.put("timestamp",      Instant.now().toString());

        send(driftSignalsTopic, modelVersion + ":" + feature, signal);
        log.info("Drift signal published: feature={} psi={} driftDetected={}", feature, psiScore, driftDetected);
    }

    private void send(String topic, String key, Object payload) {
        kafkaTemplate.send(topic, key, payload).whenComplete((r, ex) -> {
            if (ex != null) {
                log.error("Kafka send failed topic={} key={}: {}", topic, key, ex.getMessage());
            } else {
                log.debug("Kafka sent topic={} key={} partition={} offset={}",
                        topic, key,
                        r.getRecordMetadata().partition(),
                        r.getRecordMetadata().offset());
            }
        });
    }
}
