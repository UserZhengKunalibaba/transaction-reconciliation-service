package com.transactionprocessor.transactionprocessor.service;

import com.transactionprocessor.transactionprocessor.domain.SourceType;
import com.transactionprocessor.transactionprocessor.domain.UnifiedTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for central synthetic-time assignment. No Spring, no database.
 */
class SyntheticTimestampTest {

    private static UnifiedTransaction row(boolean dateOnly, LocalDateTime ts) {
        UnifiedTransaction u = new UnifiedTransaction();
        u.setTimestamp(ts);
        u.setDateOnly(dateOnly);
        u.setPayee("METRO");
        u.setAmount(new BigDecimal("-2.00"));
        u.setAmountCurrency("MYR");
        u.setSource(SourceType.TNG);
        return u;
    }

    @Test
    void dateOnlyRowsGetDeterministicSequencePreservingOrder() {
        List<UnifiedTransaction> rows = new ArrayList<>(List.of(
                row(true, LocalDateTime.of(2026, 9, 10, 0, 0)),
                row(true, LocalDateTime.of(2026, 9, 10, 0, 0)),
                row(true, LocalDateTime.of(2026, 9, 10, 0, 0))));
        StatementProcessingService.assignSyntheticTimestamps(rows, 3, new ArrayList<>());
        assertEquals(LocalDateTime.of(2026, 9, 10, 0, 0), rows.get(0).getTimestamp());
        assertEquals(LocalDateTime.of(2026, 9, 10, 0, 3), rows.get(1).getTimestamp());
        assertEquals(LocalDateTime.of(2026, 9, 10, 0, 6), rows.get(2).getTimestamp());
        assertTrue(rows.stream().noneMatch(UnifiedTransaction::isDateOnly));
    }

    @Test
    void sameInputAlwaysProducesSameSequence() {
        List<UnifiedTransaction> first = new ArrayList<>(List.of(
                row(true, LocalDateTime.of(2026, 9, 10, 0, 0)),
                row(true, LocalDateTime.of(2026, 9, 10, 0, 0))));
        List<UnifiedTransaction> second = new ArrayList<>(List.of(
                row(true, LocalDateTime.of(2026, 9, 10, 0, 0)),
                row(true, LocalDateTime.of(2026, 9, 10, 0, 0))));
        StatementProcessingService.assignSyntheticTimestamps(first, 3, new ArrayList<>());
        StatementProcessingService.assignSyntheticTimestamps(second, 3, new ArrayList<>());
        assertEquals(first.get(0).getTimestamp(), second.get(0).getTimestamp());
        assertEquals(first.get(1).getTimestamp(), second.get(1).getTimestamp());
    }

    @Test
    void realTimestampsAreNeverReplaced() {
        List<UnifiedTransaction> rows = new ArrayList<>(List.of(
                row(false, LocalDateTime.of(2026, 9, 10, 10, 1)),
                row(true, LocalDateTime.of(2026, 9, 10, 0, 0)),
                row(false, LocalDateTime.of(2026, 9, 10, 14, 37))));
        StatementProcessingService.assignSyntheticTimestamps(rows, 3, new ArrayList<>());
        assertEquals(LocalDateTime.of(2026, 9, 10, 10, 1), rows.get(0).getTimestamp());
        assertEquals(LocalDateTime.of(2026, 9, 10, 0, 0), rows.get(1).getTimestamp());
        assertEquals(LocalDateTime.of(2026, 9, 10, 14, 37), rows.get(2).getTimestamp());
    }

    @Test
    void originalDateIsPreserved() {
        List<UnifiedTransaction> rows = new ArrayList<>(List.of(
                row(true, LocalDateTime.of(2026, 9, 10, 0, 0)),
                row(true, LocalDateTime.of(2026, 9, 11, 0, 0))));
        StatementProcessingService.assignSyntheticTimestamps(rows, 3, new ArrayList<>());
        assertEquals(LocalDateTime.of(2026, 9, 10, 0, 0), rows.get(0).getTimestamp());
        // Per-file counter keeps counting, but each row stays on its own date.
        assertEquals(LocalDateTime.of(2026, 9, 11, 0, 3), rows.get(1).getTimestamp());
    }
}
