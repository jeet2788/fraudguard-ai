package com.fraud.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "transactions", indexes = {
    @Index(name = "idx_user_id",    columnList = "user_id"),
    @Index(name = "idx_merchant_id",columnList = "merchant_id"),
    @Index(name = "idx_created_at", columnList = "created_at")
})
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Transaction {

    @Id
    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Column(name = "user_id", nullable = false)       private String userId;
    @Column(name = "merchant_id", nullable = false)   private String merchantId;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(length = 3)   private String currency;
    @Column(length = 45)  private String ipAddress;
    @Column(length = 128) private String deviceFingerprint;

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private Channel channel;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // ── Scoring results ──────────────────────────────────────────
    @Column(name = "fraud_score", precision = 6, scale = 5)
    private Double fraudScore;

    @Enumerated(EnumType.STRING)
    private Decision decision;

    @Column(name = "nl_explanation", columnDefinition = "TEXT")
    private String nlExplanation;

    @Column(name = "model_version", length = 32)
    private String modelVersion;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "label")   // 0=legit 1=fraud — set by analysts
    private Integer label;

    public enum Channel    { MOBILE_APP, POS_TERMINAL, WEB_CHECKOUT, PARTNER_API }
    public enum Decision   { ALLOW, REVIEW, BLOCK }
}
