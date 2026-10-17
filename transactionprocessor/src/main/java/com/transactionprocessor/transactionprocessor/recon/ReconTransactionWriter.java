package com.transactionprocessor.transactionprocessor.recon;

import com.transactionprocessor.transactionprocessor.domain.ReconTransaction;
import com.transactionprocessor.transactionprocessor.domain.WalletAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

/**
 * The ONLY write path for recon output rows.
 *
 * <p>Deliberately NOT annotated with {@code @Transactional}: every statement is
 * auto-committed on its own, so a failed or retried run never rolls back
 * (or partial-rolls-back) unrelated rows. Idempotency comes from the
 * {@code UNIQUE (source_unified_id, account)} constraint — a duplicate key means
 * "already produced by an earlier run" and is counted as skipped, not an error.
 */
@Repository
public class ReconTransactionWriter {

    private static final Logger log = LoggerFactory.getLogger(ReconTransactionWriter.class);

    private static final String INSERT_SQL = "INSERT INTO recon_transaction "
            + "(source_unified_id, txn_timestamp, account, amount, amount_currency, source, "
            + "payee, description, type, category, reference, original_amount, original_currency, created_at) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private static final String DELETE_ONE_SQL = "DELETE FROM recon_transaction "
            + "WHERE source_unified_id = ? AND account = ?";

    private final JdbcTemplate jdbc;

    public ReconTransactionWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insert one row without any surrounding transaction.
     *
     * @return true when inserted, false when the row already existed (duplicate key).
     */
    public boolean insertOne(ReconTransaction r) {
        try {
            jdbc.update(INSERT_SQL,
                    r.getSourceUnifiedId(),
                    r.getTimestamp() == null ? null : Timestamp.valueOf(r.getTimestamp()),
                    r.getAccount() == null ? null : r.getAccount().name(),
                    r.getAmount(),
                    r.getAmountCurrency(),
                    r.getSource() == null ? null : r.getSource().name(),
                    r.getPayee(),
                    r.getDescription(),
                    r.getType(),
                    r.getCategory(),
                    r.getReference(),
                    r.getOriginalAmount(),
                    r.getOriginalCurrency(),
                    r.getCreatedAt() == null ? null : Timestamp.from(r.getCreatedAt()));
            return true;
        } catch (DuplicateKeyException dup) {
            // Expected on retry: identical (source_unified_id, account) already stored.
            return false;
        } catch (DataIntegrityViolationException dup) {
            // H2/PG unique-violation variants surface here; treat constraint hits as skips,
            // rethrow anything else (null violations, etc.).
            String msg = String.valueOf(dup.getMessage());
            if (msg.contains("uq_recon_source_account")
                    || msg.contains("Unique index or primary key violation")
                    || msg.contains("duplicate key")) {
                return false;
            }
            log.warn("Recon insert rejected (not a dup): {}", dup.getMessage());
            throw dup;
        }
    }

    /**
     * Revoke one derived row (e.g. a stale self-transfer mirror) without any
     * surrounding transaction.
     *
     * @return 1 when a row was removed, 0 when there was nothing to revoke.
     */
    public int deleteOne(Long sourceUnifiedId, WalletAccount account) {
        return jdbc.update(DELETE_ONE_SQL, sourceUnifiedId,
                account == null ? null : account.name());
    }
}
