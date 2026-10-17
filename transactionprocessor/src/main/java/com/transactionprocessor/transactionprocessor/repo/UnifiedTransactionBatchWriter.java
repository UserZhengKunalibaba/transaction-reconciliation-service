package com.transactionprocessor.transactionprocessor.repo;

import com.transactionprocessor.transactionprocessor.domain.UnifiedTransaction;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * The ONLY write path for unified transactions: a single JDBC batch INSERT per
 * chunk. No per-row SELECTs, no persistence-context overhead.
 *
 * <p>{@link Transactional} here covers the batch write itself (an implementation
 * detail the database requires) — it is not an application-level processing
 * transaction. The file-processing workflow runs outside any transaction, so a
 * failed write leaves the database unchanged with no state to clean up.
 */
@Repository
public class UnifiedTransactionBatchWriter {

    private static final String INSERT_SQL = "INSERT INTO unified_transaction "
            + "(txn_timestamp, payee, amount, amount_currency, source, reference, type, "
            + "account, category, description, original_amount, original_currency, created_at) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private final JdbcTemplate jdbc;

    public UnifiedTransactionBatchWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public int[] batchInsert(List<UnifiedTransaction> rows) {
        return jdbc.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                UnifiedTransaction u = rows.get(i);
                ps.setTimestamp(1, Timestamp.valueOf(u.getTimestamp()));
                ps.setString(2, u.getPayee());
                ps.setBigDecimal(3, u.getAmount());
                ps.setString(4, u.getAmountCurrency());
                ps.setString(5, u.getSource().name());
                ps.setString(6, u.getReference());
                ps.setString(7, u.getType());
                ps.setString(8, u.getAccount() == null ? null : u.getAccount().name());
                ps.setString(9, u.getCategory());
                ps.setString(10, u.getDescription());
                ps.setBigDecimal(11, u.getOriginalAmount());
                ps.setString(12, u.getOriginalCurrency());
                ps.setTimestamp(13, u.getCreatedAt() == null ? null
                        : Timestamp.from(u.getCreatedAt()));
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
    }
}
