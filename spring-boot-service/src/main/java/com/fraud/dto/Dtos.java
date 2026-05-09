package com.fraud.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

// ════════════════════════════════════════════════════════════════════════════
// AUTH
// ════════════════════════════════════════════════════════════════════════════

@Data public static class LoginRequest {
    @NotBlank private String username;
    @NotBlank private String password;
}

@Data @Builder public static class LoginResponse {
    private String token;
    private String tokenType;
    private long   expiresIn;
    private String username;
    private List<String> roles;
}

@Data public static class RegisterRequest {
    @NotBlank @Size(min=3, max=32) private String username;
    @NotBlank @Size(min=6)         private String password;
    @NotBlank @Email               private String email;
    private List<String> roles;
}

// ════════════════════════════════════════════════════════════════════════════
// TRANSACTION
// ════════════════════════════════════════════════════════════════════════════

@Data @Builder public static class TransactionRequest {
    @NotBlank                               private String     transactionId;
    @NotBlank                               private String     userId;
    @NotBlank                               private String     merchantId;
    @NotNull @Positive                      private BigDecimal amount;
    @NotBlank @Size(min=3, max=3)           private String     currency;
    @NotBlank                               private String     channel;
    private String ipAddress;
    private String deviceFingerprint;
    private Instant timestamp;
}

@Data @Builder @JsonInclude(JsonInclude.Include.NON_NULL)
public static class TransactionResponse {
    private String              transactionId;
    private double              fraudScore;
    private String              decision;
    private String              nlExplanation;
    private Map<String, Double> shapValues;
    private String              modelVersion;
    private Instant             processedAt;
}

// ════════════════════════════════════════════════════════════════════════════
// SCORING (Spring Boot ↔ Python ML service)
// ════════════════════════════════════════════════════════════════════════════

@Data @Builder public static class ScoringRequest {
    private String  transactionId;
    private String  userId;
    private String  merchantId;
    private double  amount;
    private String  currency;
    private String  channel;
    private String  deviceFingerprint;
    private double  velocity;
    private double  geoDistance;
    private double  merchantRisk;
    private double  timeOfDay;
    private int     dayOfWeek;
    private double  amountZscore;
    private double  userAvgAmount;
    private int     userTxnCount24h;
}

@Data public static class ScoringResponse {
    private String              transactionId;
    private double              fraudScore;
    private Map<String, Double> shapValues;
    private String              modelVersion;
    private double              latencyMs;
}

@Data public static class ExplanationRequest {
    private String              transactionId;
    private double              fraudScore;
    private Map<String, Double> shapValues;
    private String              decision;
}

@Data public static class ExplanationResponse {
    private String transactionId;
    private String explanation;
    private String decision;
}

// ════════════════════════════════════════════════════════════════════════════
// MERCHANT
// ════════════════════════════════════════════════════════════════════════════

@Data public static class MerchantRequest {
    @NotBlank private String merchantId;
    @NotBlank private String name;
    private String webhookUrl;
    private String webhookSecret;
    private Double riskScore;
}

@Data @Builder public static class MerchantResponse {
    private String  merchantId;
    private String  name;
    private String  webhookUrl;
    private Double  riskScore;
    private boolean active;
    private Instant createdAt;
}

// ════════════════════════════════════════════════════════════════════════════
// ADMIN / DASHBOARD
// ════════════════════════════════════════════════════════════════════════════

@Data @Builder public static class DashboardStats {
    private long    totalTransactions24h;
    private long    blocked24h;
    private long    reviews24h;
    private long    allowed24h;
    private double  avgFraudScore24h;
    private String  modelVersion;
    private double  modelAuc;
}

@Data public static class LabelRequest {
    @NotNull private Integer label;    // 0 = legit, 1 = fraud
    private String analystNote;
}

// ════════════════════════════════════════════════════════════════════════════
// WRAPPER — keeps DTOs namespaced
// ════════════════════════════════════════════════════════════════════════════

public class Dtos {
    private Dtos() {}
}
