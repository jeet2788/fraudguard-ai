package com.fraud.repository;

import com.fraud.model.Transaction;
import com.fraud.model.Transaction.Decision;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, String> {

    Page<Transaction> findByUserId(String userId, Pageable pageable);

    Page<Transaction> findByMerchantId(String merchantId, Pageable pageable);

    Page<Transaction> findByDecision(Decision decision, Pageable pageable);

    List<Transaction> findByUserIdAndCreatedAtAfter(String userId, Instant after);

    @Query("""
        SELECT t FROM Transaction t
        WHERE t.createdAt BETWEEN :from AND :to
        ORDER BY t.createdAt DESC
    """)
    Page<Transaction> findByDateRange(@Param("from") Instant from,
                                      @Param("to")   Instant to,
                                      Pageable pageable);

    @Query("""
        SELECT t FROM Transaction t
        WHERE t.label IS NULL
          AND t.decision IN ('BLOCK','REVIEW')
        ORDER BY t.fraudScore DESC
    """)
    Page<Transaction> findUnlabelledAlerts(Pageable pageable);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.decision = :d AND t.createdAt >= :since")
    long countByDecisionSince(@Param("d") Decision d, @Param("since") Instant since);

    @Query("SELECT AVG(t.fraudScore) FROM Transaction t WHERE t.createdAt >= :since")
    Double avgFraudScoreSince(@Param("since") Instant since);
}
