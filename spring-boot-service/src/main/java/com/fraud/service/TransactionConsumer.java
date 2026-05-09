package com.fraud.service;

import com.fraud.model.Transaction;
import com.fraud.service.DecisionEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consumes raw transactions from transactions.raw (12 partitions).
 * Used for async ingestion path — POST /v1/transactions/async.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionConsumer {

    private final DecisionEngineService decisionEngine;

    @KafkaListener(
        topics      = "${fraud.kafka.topic.transactions-raw}",
        groupId     = "${spring.kafka.consumer.group-id}",
        concurrency = "12"
    )
    public void consume(
            @Payload Transaction txn,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment ack) {

        log.debug("Consuming txn={} partition={} offset={}", txn.getTransactionId(), partition, offset);
        try {
            decisionEngine.evaluate(txn);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process txn={}: {}", txn.getTransactionId(), e.getMessage(), e);
            // No ack → Kafka will retry
        }
    }
}
