package com.transactionprocessor.transactionprocessor.web;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the date-filter upper bound: it must be exactly start-of-next-day so
 * microsecond-precision databases cannot round it up to midnight and leak the
 * next day's 00:00:00 rows into this period's view.
 */
class TransactionDateBoundTest {

    @Test
    void exclusiveBoundIsExactlyNextMidnight() {
        LocalDateTime bound = TransactionController.toExclusiveBound(LocalDate.of(2026, 6, 30));
        assertEquals(LocalDateTime.of(2026, 7, 1, 0, 0), bound);
        assertEquals(0, bound.getNano(), "no sub-second part to round");
    }
}
