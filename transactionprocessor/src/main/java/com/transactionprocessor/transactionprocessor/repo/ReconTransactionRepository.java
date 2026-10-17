package com.transactionprocessor.transactionprocessor.repo;

import com.transactionprocessor.transactionprocessor.domain.ReconTransaction;
import com.transactionprocessor.transactionprocessor.domain.WalletAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Read side for the recon output table (paginated UI + retry pre-check).
 * Writes go through {@code ReconTransactionWriter} (row-by-row JDBC, no transaction),
 * never through this repository, so retries stay idempotent without a write transaction.
 */
public interface ReconTransactionRepository extends JpaRepository<ReconTransaction, Long>,
        JpaSpecificationExecutor<ReconTransaction> {

    long countByAccount(WalletAccount account);
}
