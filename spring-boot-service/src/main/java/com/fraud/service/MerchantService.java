package com.fraud.service;

import com.fraud.model.Merchant;
import com.fraud.model.Transaction;
import com.fraud.repository.MerchantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// ─── Merchant Service ─────────────────────────────────────────────────────────
@Service
@RequiredArgsConstructor
class MerchantService {

    private final MerchantRepository merchantRepo;
    private final RedisTemplate<String, Object> redisTemplate;
    private static final String PFX = "merchant:risk:";

    public double getRiskScore(String merchantId) {
        Object cached = redisTemplate.opsForValue().get(PFX + merchantId);
        if (cached != null) {
            try { return Double.parseDouble(cached.toString()); }
            catch (NumberFormatException ignored) {}
        }
        return merchantRepo.findById(merchantId)
                .map(m -> {
                    double score = m.getRiskScore() != null ? m.getRiskScore() : 0.5;
                    redisTemplate.opsForValue().set(PFX + merchantId, String.valueOf(score), 1, TimeUnit.HOURS);
                    return score;
                })
                .orElse(0.5);
    }

    public Merchant create(Merchant merchant) {
        merchant.setCreatedAt(Instant.now());
        merchant.setUpdatedAt(Instant.now());
        return merchantRepo.save(merchant);
    }

    public Merchant update(Merchant merchant) {
        merchant.setUpdatedAt(Instant.now());
        redisTemplate.delete(PFX + merchant.getMerchantId());
        return merchantRepo.save(merchant);
    }

    public List<Merchant> listAll() { return merchantRepo.findAll(); }

    public Merchant getById(String id) {
        return merchantRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Merchant not found: " + id));
    }
}

// ─── Webhook Service ──────────────────────────────────────────────────────────
@Slf4j
@Service
@RequiredArgsConstructor
class WebhookService {

    private final MerchantRepository merchantRepo;
    private final WebClient.Builder  webClientBuilder;

    public void notifyMerchant(Transaction txn) {
        merchantRepo.findById(txn.getMerchantId()).ifPresent(merchant -> {
            if (merchant.getWebhookUrl() == null || !merchant.isActive()) return;

            Map<String, Object> payload = new HashMap<>();
            payload.put("transaction_id", txn.getTransactionId());
            payload.put("fraud_score",    txn.getFraudScore());
            payload.put("decision",       txn.getDecision().name());
            payload.put("processed_at",   Instant.now().toString());

            webClientBuilder.build()
                    .post()
                    .uri(merchant.getWebhookUrl())
                    .header("X-Fraud-Signature", sign(payload, merchant.getWebhookSecret()))
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .subscribe(
                        r  -> log.debug("Webhook delivered to merchant={} status={}", txn.getMerchantId(), r.getStatusCode()),
                        ex -> log.warn("Webhook failed merchant={}: {}", txn.getMerchantId(), ex.getMessage())
                    );
        });
    }

    private String sign(Map<String, Object> payload, String secret) {
        // HMAC-SHA256 in production — simplified here
        return "sha256=" + (secret != null ? secret.hashCode() : "nosecret");
    }
}
