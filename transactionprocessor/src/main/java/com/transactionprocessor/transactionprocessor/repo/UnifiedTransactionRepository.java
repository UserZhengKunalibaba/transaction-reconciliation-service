package com.transactionprocessor.transactionprocessor.repo;

import com.transactionprocessor.transactionprocessor.domain.UnifiedTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Read side for the unified transaction table: batch deduplication lookups
 * plus the paginated read API backing {@code GET /api/transactions}.
 * Writes go through the JDBC batch writer (single batch INSERT, no per-row SELECTs).
 */
public interface UnifiedTransactionRepository extends JpaRepository<UnifiedTransaction, Long>,
        JpaSpecificationExecutor<UnifiedTransaction> {

    /**
     * Batch candidate lookup for deduplication: all rows in the timestamp window whose
     * payee is among the candidates. Callers match the full (timestamp, payee, amount)
     * triple in memory.
     */
    @Query("select u from UnifiedTransaction u where u.timestamp between :min and :max and u.payee in :payees")
    List<UnifiedTransaction> findDedupCandidates(LocalDateTime min, LocalDateTime max, List<String> payees);
}
