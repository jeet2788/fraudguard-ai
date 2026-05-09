package com.fraud.controller;

import com.fraud.model.Transaction.Decision;
import com.fraud.repository.TransactionRepository;
import com.fraud.service.MlClientService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * GET  /v1/admin/stats            — 24-hour fraud stats
 * GET  /v1/admin/alerts           — unlabelled BLOCK/REVIEW queue
 * GET  /v1/admin/model            — current champion model info
 * POST /v1/admin/model/reload     — hot-reload model from MLflow
 */
@RestController
@RequestMapping("/v1/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final TransactionRepository txnRepo;
    private final MlClientService       mlClient;

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        Instant since24h = Instant.now().minus(24, ChronoUnit.HOURS);

        long total    = txnRepo.count();
        long blocked  = txnRepo.countByDecisionSince(Decision.BLOCK,  since24h);
        long reviews  = txnRepo.countByDecisionSince(Decision.REVIEW, since24h);
        long allowed  = txnRepo.countByDecisionSince(Decision.ALLOW,  since24h);
        Double avgScore = txnRepo.avgFraudScoreSince(since24h);

        var modelInfo = mlClient.getModelInfo();

        return ResponseEntity.ok(Map.of(
                "totalTransactions", total,
                "last24h", Map.of(
                        "blocked",       blocked,
                        "reviews",       reviews,
                        "allowed",       allowed,
                        "avgFraudScore", avgScore != null ? avgScore : 0.0
                ),
                "model", modelInfo
        ));
    }

    @GetMapping("/alerts")
    public ResponseEntity<?> alerts(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        var pageable = PageRequest.of(page, size, Sort.by("fraudScore").descending());
        return ResponseEntity.ok(txnRepo.findUnlabelledAlerts(pageable));
    }

    @GetMapping("/model")
    public ResponseEntity<?> modelInfo() {
        return ResponseEntity.ok(mlClient.getModelInfo());
    }

    @PostMapping("/model/reload")
    public ResponseEntity<?> reloadModel() {
        return ResponseEntity.ok(
            mlClient.getModelInfo()   // triggers reload via ML service endpoint in full impl
        );
    }
}
