package com.fraud.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "merchants")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class Merchant {

    @Id
    @Column(name = "merchant_id", length = 64)
    private String merchantId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(name = "webhook_url", length = 256)
    private String webhookUrl;

    @Column(name = "webhook_secret", length = 128)
    private String webhookSecret;

    @Column(name = "risk_score", precision = 4, scale = 3)
    private Double riskScore;          // 0.0 – 1.0

    @Column(name = "active")
    private boolean active = true;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;
}
