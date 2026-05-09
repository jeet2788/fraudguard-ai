package com.fraud.controller;

import com.fraud.dto.Dtos.*;
import com.fraud.model.Transaction;
import com.fraud.repository.TransactionRepository;
import com.fraud.service.DecisionEngineService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * POST  /v1/transactions            — sync score (< 200ms SLA)
 * POST  /v1/transactions/async      — async via Kafka (202 Accepted)
 * GET   /v1/transactions/{id}       — fetch scored result
 * GET   /v1/transactions            — paginated list (admin/analyst)
 * PATCH /v1/transactions/{id}/label — analyst labels a transaction
 */
@RestController
@RequestMapping("/v1/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final DecisionEngineService decisionEngine;
    private final TransactionRepository txnRepo;
    private final KafkaTemplate<String, Object> kafka;

    // ── Sync ingestion ────────────────────────────────────────────────────────
    @PostMapping
    public ResponseEntity<TransactionResponse> submit(
            @Valid @RequestBody TxnRequest req) {

        Transaction txn = toEntity(req);
        TransactionResponse result = decisionEngine.evaluate(txn);
        return ResponseEntity.ok(result);
    }

    // ── Async ingestion ───────────────────────────────────────────────────────
    @PostMapping("/async")
    public ResponseEntity<Map<String, String>> submitAsync(
            @Valid @RequestBody TxnRequest req) {

        Transaction txn = toEntity(req);
        kafka.send("transactions.raw", txn.getTransactionId(), txn);
        return ResponseEntity.accepted().body(Map.of(
                "transactionId", txn.getTransactionId(),
                "status", "QUEUED"
        ));
    }

    // ── Get single transaction ────────────────────────────────────────────────
    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponse> getById(@PathVariable String id) {
        return txnRepo.findById(id)
                .map(t -> ResponseEntity.ok(toResponse(t)))
                .orElse(ResponseEntity.notFound().build());
    }

    // ── Paginated list ────────────────────────────────────────────────────────
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public Page<TransactionResponse> list(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)    String decision) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        if (decision != null) {
            return txnRepo.findByDecision(Transaction.Decision.valueOf(decision), pageable)
                          .map(this::toResponse);
        }
        return txnRepo.findAll(pageable).map(this::toResponse);
    }

    // ── Analyst label ─────────────────────────────────────────────────────────
    @PatchMapping("/{id}/label")
    @PreAuthorize("hasAnyRole('ADMIN','ANALYST')")
    public ResponseEntity<Map<String, Object>> label(
            @PathVariable String id,
            @RequestBody LabelBody body) {

        return txnRepo.findById(id).map(t -> {
            t.setLabel(body.getLabel());
            txnRepo.save(t);
            return ResponseEntity.ok(Map.<String, Object>of(
                    "transactionId", id,
                    "label", body.getLabel(),
                    "labelledAt", Instant.now()
            ));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Helpers ────────────────────────────────────────────────────────────────
    private Transaction toEntity(TxnRequest r) {
        return Transaction.builder()
                .transactionId(r.getTransactionId())
                .userId(r.getUserId())
                .merchantId(r.getMerchantId())
                .amount(r.getAmount())
                .currency(r.getCurrency())
                .channel(Transaction.Channel.valueOf(r.getChannel()))
                .ipAddress(r.getIpAddress())
                .deviceFingerprint(r.getDeviceFingerprint())
                .createdAt(r.getTimestamp() != null ? r.getTimestamp() : Instant.now())
                .build();
    }

    private TransactionResponse toResponse(Transaction t) {
        return TransactionResponse.builder()
                .transactionId(t.getTransactionId())
                .fraudScore(t.getFraudScore() != null ? t.getFraudScore() : -1)
                .decision(t.getDecision() != null ? t.getDecision().name() : "PENDING")
                .nlExplanation(t.getNlExplanation())
                .modelVersion(t.getModelVersion())
                .processedAt(t.getProcessedAt())
                .build();
    }

    // ── Inner request / label DTOs ─────────────────────────────────────────────
    @Data public static class TxnRequest {
        @NotBlank                    String     transactionId;
        @NotBlank                    String     userId;
        @NotBlank                    String     merchantId;
        @NotNull @Positive           BigDecimal amount;
        @NotBlank @Size(min=3,max=3) String     currency;
        @NotBlank                    String     channel;
        String ipAddress;
        String deviceFingerprint;
        Instant timestamp;
    }

    @Data public static class LabelBody {
        @NotNull @Min(0) @Max(1) Integer label;
    }
}
