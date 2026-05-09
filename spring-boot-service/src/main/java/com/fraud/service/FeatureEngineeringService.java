package com.fraud.service;

import com.fraud.dto.Dtos.*;
import com.fraud.model.Transaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Computes real-time features backed by Redis rolling windows.
 * Features: velocity · geo_distance · merchant_risk ·
 *           time_of_day · amount_zscore · user_txn_count_24h
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureEngineeringService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final MerchantService merchantService;

    private static final String PFX_VELOCITY   = "feat:velocity:";
    private static final String PFX_AMOUNTS    = "feat:amounts:";
    private static final long   TTL_H          = 25;   // hours

    public ScoringRequest buildScoringRequest(Transaction txn) {
        String userId     = txn.getUserId();
        double amount     = txn.getAmount().doubleValue();
        Instant ts        = txn.getCreatedAt() != null ? txn.getCreatedAt() : Instant.now();

        double velocity       = getVelocity(userId);
        double merchantRisk   = merchantService.getRiskScore(txn.getMerchantId());
        double timeOfDay      = ts.atZone(ZoneOffset.UTC).getHour() / 23.0;
        int    dayOfWeek      = ts.atZone(ZoneOffset.UTC).getDayOfWeek().getValue();
        double[] stats        = getUserAmountStats(userId);
        double amountZscore   = (amount - stats[0]) / (stats[1] + 1e-9);
        int    txnCount24h    = getTxnCount24h(userId);

        // Update state
        pushAmount(userId, amount);
        recordVelocity(userId);

        return ScoringRequest.builder()
                .transactionId(txn.getTransactionId())
                .userId(userId)
                .merchantId(txn.getMerchantId())
                .amount(amount)
                .currency(txn.getCurrency())
                .channel(txn.getChannel().name())
                .deviceFingerprint(txn.getDeviceFingerprint())
                .velocity(velocity)
                .geoDistance(0.0)       // enriched by ML service via MaxMind
                .merchantRisk(merchantRisk)
                .timeOfDay(timeOfDay)
                .dayOfWeek(dayOfWeek)
                .amountZscore(amountZscore)
                .userAvgAmount(stats[0])
                .userTxnCount24h(txnCount24h)
                .build();
    }

    // ── velocity: sorted-set sliding window ──────────────────────────────────
    private double getVelocity(String userId) {
        String key  = PFX_VELOCITY + userId;
        long   now  = Instant.now().toEpochMilli();
        long   hour = now - 3_600_000L;
        redisTemplate.opsForZSet().removeRangeByScore(key, 0, hour);
        Long c = redisTemplate.opsForZSet().zCard(key);
        return c == null ? 0 : c.doubleValue();
    }

    private void recordVelocity(String userId) {
        String key = PFX_VELOCITY + userId;
        long   now = Instant.now().toEpochMilli();
        redisTemplate.opsForZSet().add(key, String.valueOf(now), now);
        redisTemplate.expire(key, TTL_H, TimeUnit.HOURS);
    }

    // ── amount stats: list of last 100 txns ──────────────────────────────────
    private double[] getUserAmountStats(String userId) {
        List<Object> raw = redisTemplate.opsForList().range(PFX_AMOUNTS + userId, 0, -1);
        if (raw == null || raw.isEmpty()) return new double[]{0, 1};
        double[] vals = raw.stream().mapToDouble(o -> Double.parseDouble(o.toString())).toArray();
        double mean = 0;
        for (double v : vals) mean += v;
        mean /= vals.length;
        double var = 0;
        for (double v : vals) var += (v - mean) * (v - mean);
        return new double[]{mean, Math.sqrt(var / vals.length + 1e-9)};
    }

    private void pushAmount(String userId, double amount) {
        String key = PFX_AMOUNTS + userId;
        redisTemplate.opsForList().rightPush(key, String.valueOf(amount));
        redisTemplate.opsForList().trim(key, -100, -1);
        redisTemplate.expire(key, TTL_H, TimeUnit.HOURS);
    }

    private int getTxnCount24h(String userId) {
        Long c = redisTemplate.opsForZSet().zCard(PFX_VELOCITY + userId);
        return c == null ? 0 : c.intValue();
    }
}
