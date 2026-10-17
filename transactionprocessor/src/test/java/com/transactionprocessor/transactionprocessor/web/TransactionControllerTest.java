package com.transactionprocessor.transactionprocessor.web;

import com.transactionprocessor.transactionprocessor.domain.SourceType;
import com.transactionprocessor.transactionprocessor.domain.UnifiedTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TransactionControllerTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    com.transactionprocessor.transactionprocessor.repo.UnifiedTransactionRepository repo;

    @BeforeEach
    void clean() {
        repo.deleteAll();
    }

    private static UnifiedTransaction row(LocalDateTime ts, String payee, String amount, SourceType source) {
        UnifiedTransaction u = new UnifiedTransaction();
        u.setTimestamp(ts);
        u.setPayee(payee);
        u.setAmount(new BigDecimal(amount));
        u.setAmountCurrency("MYR");
        u.setSource(source);
        u.setType("EXPENSE");
        return u;
    }

    private void seed() {
        repo.save(row(LocalDateTime.of(2026, 9, 10, 10, 0), "METRO", "-2.00", SourceType.TNG));
        repo.save(row(LocalDateTime.of(2026, 9, 11, 10, 0), "STARBUCKS", "-15.00", SourceType.ALIPAY));
        repo.save(row(LocalDateTime.of(2026, 9, 12, 10, 0), "METRO STATION", "-3.00", SourceType.TNG));
    }

    @Test
    void listIsPaginated() throws Exception {
        seed();
        mvc.perform(get("/api/transactions").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void listFiltersBySource() throws Exception {
        seed();
        mvc.perform(get("/api/transactions").param("source", "TNG").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void listFiltersByPayeeSubstringCaseInsensitive() throws Exception {
        seed();
        mvc.perform(get("/api/transactions").param("payee", "metro").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void listFiltersByDateRange() throws Exception {
        seed();
        mvc.perform(get("/api/transactions")
                        .param("from", "2026-09-11").param("to", "2026-09-11").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].payee").value("STARBUCKS"));
    }

    @Test
    void listSortsByAmountAsc() throws Exception {
        seed();
        mvc.perform(get("/api/transactions").param("size", "20").param("sort", "amount,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].payee").value("STARBUCKS"))
                .andExpect(jsonPath("$.content[2].payee").value("METRO"));
    }

    @Test
    void listSortsByPayeeDesc() throws Exception {
        seed();
        mvc.perform(get("/api/transactions").param("size", "20").param("sort", "payee,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].payee").value("STARBUCKS"));
    }

    @Test
    void listIgnoresUnknownSortProperty() throws Exception {
        seed();
        mvc.perform(get("/api/transactions").param("size", "20").param("sort", "dateOnly,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void getOneReturnsRow() throws Exception {        UnifiedTransaction saved = repo.save(
                row(LocalDateTime.of(2026, 9, 10, 10, 0), "METRO", "-2.00", SourceType.TNG));
        mvc.perform(get("/api/transactions/" + saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payee").value("METRO"));
    }

    @Test
    void getMissingReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/transactions/999999"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteOneRemovesRow() throws Exception {
        UnifiedTransaction saved = repo.save(
                row(LocalDateTime.of(2026, 9, 10, 10, 0), "METRO", "-2.00", SourceType.TNG));
        mvc.perform(delete("/api/transactions/" + saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(1));
        assertEquals(0, repo.count());
    }

    @Test
    void deleteMissingOneReturnsBadRequest() throws Exception {
        mvc.perform(delete("/api/transactions/999999"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteBatchRemovesSelectedIds() throws Exception {
        seed();
        var ids = repo.findAll().stream().map(UnifiedTransaction::getId).toList();
        String param = ids.get(0) + "," + ids.get(1);
        mvc.perform(delete("/api/transactions").param("ids", param))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(2));
        assertEquals(1, repo.count());
    }

    @Test
    void deleteBatchWithoutIdsReturnsBadRequest() throws Exception {
        seed();
        mvc.perform(delete("/api/transactions"))
                .andExpect(status().isBadRequest());
        assertEquals(3, repo.count());
    }

    @Test
    void deleteAllCleansTable() throws Exception {
        seed();
        mvc.perform(delete("/api/transactions/all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(3));
        assertEquals(0, repo.count());
    }

    @Test
    void subtotalCoversAllPagesIgnoringPagination() throws Exception {
        seed();
        mvc.perform(get("/api/transactions/subtotal").param("size", "2").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.subtotal").value(-20.00))
                .andExpect(jsonPath("$.byCurrency.MYR").value(-20.00));
    }

    @Test
    void subtotalRespectsFilters() throws Exception {
        seed();
        mvc.perform(get("/api/transactions/subtotal").param("source", "TNG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.subtotal").value(-5.00));
    }

    @Test
    void subtotalOnEmptyResultIsZero() throws Exception {
        seed();
        mvc.perform(get("/api/transactions/subtotal").param("payee", "no-such-payee"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.subtotal").value(0));
    }

    @Test
    void toDateExcludesNextDayMidnightRow() throws Exception {
        // Synthetic timestamps start files at exactly 00:00:00, so the next
        // month's first row sits precisely on the rounding edge. June filter
        // must not leak it in (June-2026 case: July's -20.00 GRABPAY row
        // dragged the June subtotal from 729.19 down to 709.19).
        repo.save(row(LocalDateTime.of(2026, 6, 30, 15, 0), "JUNE SHOP", "-10.00", SourceType.TNG));
        repo.save(row(LocalDateTime.of(2026, 7, 1, 0, 0), "JULY SHOP", "-20.00", SourceType.TNG));
        mvc.perform(get("/api/transactions")
                        .param("from", "2026-06-01").param("to", "2026-06-30").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].payee").value("JUNE SHOP"));
        mvc.perform(get("/api/transactions/subtotal")
                        .param("from", "2026-06-01").param("to", "2026-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.subtotal").value(-10.00));
    }
}
