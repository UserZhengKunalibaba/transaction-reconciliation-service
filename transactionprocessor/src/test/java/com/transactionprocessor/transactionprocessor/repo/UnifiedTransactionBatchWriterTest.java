package com.transactionprocessor.transactionprocessor.repo;

import com.transactionprocessor.transactionprocessor.domain.SourceType;
import com.transactionprocessor.transactionprocessor.domain.UnifiedTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the write path is a true batch INSERT (one prepared statement for N rows),
 * not one SQL operation per transaction. Plain JUnit + H2, no Spring context.
 */
class UnifiedTransactionBatchWriterTest {

    /** Counts {@code prepareStatement} calls on connections from the wrapped DataSource. */
    static class CountingDataSource implements InvocationHandler {
        private final DataSource delegate;
        final AtomicInteger prepareStatements = new AtomicInteger();

        CountingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        DataSource proxy() {
            return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{DataSource.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object result = method.invoke(delegate, args);
            if (result instanceof Connection conn && method.getName().startsWith("getConnection")) {
                return Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{Connection.class},
                        (p, m, a) -> {
                            if (m.getName().equals("prepareStatement")) {
                                prepareStatements.incrementAndGet();
                            }
                            return m.invoke(conn, a);
                        });
            }
            return result;
        }
    }

    @Test
    void batchInsertUsesOnePreparedStatementForManyRows() {
        DriverManagerDataSource raw = new DriverManagerDataSource(
                "jdbc:h2:mem:batchtest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        CountingDataSource counting = new CountingDataSource(raw);
        JdbcTemplate jdbc = new JdbcTemplate(counting.proxy());
        jdbc.execute("CREATE TABLE unified_transaction ("
                + "id BIGSERIAL PRIMARY KEY, txn_timestamp TIMESTAMP NOT NULL, "
                + "payee VARCHAR(256) NOT NULL, amount NUMERIC(14,2) NOT NULL, "
                + "amount_currency VARCHAR(8) NOT NULL, source VARCHAR(32) NOT NULL, "
                + "reference VARCHAR(256), type VARCHAR(16), account VARCHAR(16), "
                + "category VARCHAR(64), description VARCHAR(1024), "
                + "original_amount NUMERIC(14,2), original_currency VARCHAR(8), "
                + "created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(), "
                + "CONSTRAINT uq_unified_txn_key UNIQUE (txn_timestamp, payee, amount))");

        List<UnifiedTransaction> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UnifiedTransaction u = new UnifiedTransaction();
            u.setTimestamp(LocalDateTime.of(2026, 9, 10, 10, i));
            u.setPayee("METRO");
            u.setAmount(new BigDecimal("-2.00"));
            u.setAmountCurrency("MYR");
            u.setSource(SourceType.TNG);
            u.setType("EXPENSE");
            rows.add(u);
        }

        counting.prepareStatements.set(0);
        new UnifiedTransactionBatchWriter(jdbc).batchInsert(rows);

        assertEquals(1, counting.prepareStatements.get(),
                "batch insert must use a single prepared statement for all rows");
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM unified_transaction", Integer.class));
    }
}
