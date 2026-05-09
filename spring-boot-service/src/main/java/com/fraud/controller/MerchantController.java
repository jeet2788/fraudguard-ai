package com.fraud.controller;

import com.fraud.model.Merchant;
import com.fraud.service.MerchantService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * POST   /v1/merchants         — register a merchant
 * GET    /v1/merchants         — list all merchants
 * GET    /v1/merchants/{id}    — get merchant by id
 * PUT    /v1/merchants/{id}    — update (webhook URL, risk score)
 * DELETE /v1/merchants/{id}    — deactivate
 */
@RestController
@RequestMapping("/v1/merchants")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class MerchantController {

    private final MerchantService merchantService;

    @PostMapping
    public ResponseEntity<Merchant> create(@Valid @RequestBody MerchantRequest req) {
        Merchant m = Merchant.builder()
                .merchantId(req.getMerchantId())
                .name(req.getName())
                .webhookUrl(req.getWebhookUrl())
                .webhookSecret(req.getWebhookSecret())
                .riskScore(req.getRiskScore() != null ? req.getRiskScore() : 0.5)
                .active(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return ResponseEntity.ok(merchantService.create(m));
    }

    @GetMapping
    public ResponseEntity<List<Merchant>> list() {
        return ResponseEntity.ok(merchantService.listAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Merchant> getById(@PathVariable String id) {
        return ResponseEntity.ok(merchantService.getById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Merchant> update(@PathVariable String id,
                                            @RequestBody MerchantRequest req) {
        Merchant m = merchantService.getById(id);
        if (req.getWebhookUrl()    != null) m.setWebhookUrl(req.getWebhookUrl());
        if (req.getWebhookSecret() != null) m.setWebhookSecret(req.getWebhookSecret());
        if (req.getRiskScore()     != null) m.setRiskScore(req.getRiskScore());
        if (req.getName()          != null) m.setName(req.getName());
        return ResponseEntity.ok(merchantService.update(m));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deactivate(@PathVariable String id) {
        Merchant m = merchantService.getById(id);
        m.setActive(false);
        merchantService.update(m);
        return ResponseEntity.ok(Map.of("status", "deactivated", "merchantId", id));
    }

    @Data public static class MerchantRequest {
        @NotBlank String merchantId;
        @NotBlank String name;
        String  webhookUrl;
        String  webhookSecret;
        Double  riskScore;
    }
}
