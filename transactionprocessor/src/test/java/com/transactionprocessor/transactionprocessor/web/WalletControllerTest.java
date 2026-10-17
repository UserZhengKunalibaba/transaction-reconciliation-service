package com.transactionprocessor.transactionprocessor.web;

import com.transactionprocessor.transactionprocessor.domain.ReconTransaction;
import com.transactionprocessor.transactionprocessor.domain.SourceType;
import com.transactionprocessor.transactionprocessor.domain.WalletAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WalletControllerTest {

    @Autowired MockMvc mvc;
    @Autowired com.transactionprocessor.transactionprocessor.repo.ReconTransactionRepository repo;
    @Autowired com.transactionprocessor.transactionprocessor.repo.UnifiedTransactionRepository unifiedRepo;

    @BeforeEach
    void clean() {
        repo.deleteAll();
        unifiedRepo.deleteAll();
    }

    private static ReconTransaction row(LocalDateTime ts, WalletAccount account, String amount,
                                        String payee, SourceType source, long sourceUnifiedId) {
        return row(ts, account, amount, "MYR", "EXPENSE", payee, source, sourceUnifiedId);
    }

    private static ReconTransaction row(LocalDateTime ts, WalletAccount account, String amount,
                                        String currency, String type, String payee,
                                        SourceType source, long sourceUnifiedId) {
        ReconTransaction u = new ReconTransaction();
        u.setSourceUnifiedId(sourceUnifiedId);
        u.setTimestamp(ts);
        u.setAccount(account);
        u.setPayee(payee);
        u.setAmount(new BigDecimal(amount));
        u.setAmountCurrency(currency);
        u.setSource(source);
        u.setType(type);
        u.setDescription(payee + " desc");
        u.setCategory("Food");
        return u;
    }

    private void seed() {
        repo.save(row(LocalDateTime.of(2026, 6, 1, 10, 0), WalletAccount.Maybank, "-10.00", "MAYBANK SHOP", SourceType.MAYBANK_SA, 101L));
        repo.save(row(LocalDateTime.of(2026, 7, 15, 12, 0), WalletAccount.Grab, "-8.50", "GRAB TRIP", SourceType.GRAB, 102L));
        repo.save(row(LocalDateTime.of(2026, 9, 4, 15, 51), WalletAccount.Cash, "-5.00", "TNG MERCHANT", SourceType.TNG, 103L));
        // Non-MYR rows: never exportable (the CSV has no currency column).
        repo.save(row(LocalDateTime.of(2026, 6, 5, 18, 14), WalletAccount.Cash, "50.00", "CNY", "INCOME", "G. K. PIN", SourceType.ALIPAY, 201L));
        repo.save(row(LocalDateTime.of(2026, 6, 19, 14, 0), WalletAccount.Grab, "-74.00", "THB", "EXPENSE", "BANGKOK NOODLE", SourceType.GRAB, 202L));
    }

    @Test
    void previewFiltersByAccount() throws Exception {
        seed();
        mvc.perform(get("/api/wallet/preview").param("account", "Grab").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].payee").value("GRAB TRIP"));
    }

    @Test
    void previewFiltersByTimestampRange() throws Exception {
        seed();
        mvc.perform(get("/api/wallet/preview")
                        .param("from", "2026-07-01").param("to", "2026-08-01").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].payee").value("GRAB TRIP"));
    }

    @Test
    void exportStreamsCsvWithFilters() throws Exception {
        seed();
        MvcResult r = mvc.perform(get("/api/wallet/export")
                        .param("account", "Cash")
                        .param("from", "2026-09-01").param("to", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("wallet-Cash")))
                .andReturn();
        String csv = r.getResponse().getContentAsString();
        String[] lines = csv.split("\n");
        assertEquals("Date,Account,Amount,Note,Payee,Category", lines[0]);
        assertEquals(2, lines.length);
        assertTrue(lines[1].contains("2026-09-04"));
        assertTrue(lines[1].contains("Cash"));
        assertTrue(lines[1].contains("TNG MERCHANT"));
    }

    @Test
    void exportEmptyReturns400() throws Exception {
        seed();
        mvc.perform(get("/api/wallet/export").param("account", "Grab").param("from", "2026-09-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void idsListsMatchingIdsOldestFirst() throws Exception {
        seed();
        mvc.perform(get("/api/wallet/ids").param("from", "2026-06-01").param("to", "2026-09-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get("/api/wallet/ids").param("account", "Grab"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void exportByIdsGetReturnsOnlySelected() throws Exception {
        seed();
        var all = repo.findAll();
        Long maybankId = all.stream().filter(u -> "MAYBANK SHOP".equals(u.getPayee())).findFirst().orElseThrow().getId();
        Long cashId = all.stream().filter(u -> "TNG MERCHANT".equals(u.getPayee())).findFirst().orElseThrow().getId();
        MvcResult r = mvc.perform(get("/api/wallet/export").param("ids", maybankId + "," + cashId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("wallet-selected")))
                .andReturn();
        String csv = r.getResponse().getContentAsString();
        String[] lines = csv.split("\n");
        assertEquals(3, lines.length); // header + 2 selected
        assertTrue(lines[1].contains("MAYBANK SHOP"));
        assertTrue(lines[2].contains("TNG MERCHANT"));
    }

    @Test
    void exportByIdsPostReturnsOnlySelected() throws Exception {
        seed();
        var all = repo.findAll();
        Long grabId = all.stream().filter(u -> "GRAB TRIP".equals(u.getPayee())).findFirst().orElseThrow().getId();
        MvcResult r = mvc.perform(post("/api/wallet/export")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + grabId + "]}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("wallet-selected")))
                .andReturn();
        String csv = r.getResponse().getContentAsString();
        String[] lines = csv.split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[1].contains("GRAB TRIP"));
    }

    @Test
    void exportByUnknownIdsReturns400() throws Exception {
        seed();
        mvc.perform(post("/api/wallet/export")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[999998,999999]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void exportByEmptyIdsReturns400InsteadOfFullTable() throws Exception {
        seed();
        mvc.perform(post("/api/wallet/export")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

        @Test
    void exportReadsReconTableNotUnified() throws Exception {

        // A unified row with no recon output must never leak into the export.
        seed();
        com.transactionprocessor.transactionprocessor.domain.UnifiedTransaction orphan =
                new com.transactionprocessor.transactionprocessor.domain.UnifiedTransaction();
        orphan.setTimestamp(LocalDateTime.of(2026, 8, 1, 10, 0));
        orphan.setPayee("UNIFIED ONLY ROW");
        orphan.setAmount(new BigDecimal("-99.99"));
        orphan.setAmountCurrency("MYR");
        orphan.setSource(SourceType.MAYBANK_SA);
        orphan.setType("EXPENSE");
        orphan.setAccount(WalletAccount.Maybank);
        orphan.setDescription("unified orphan");
        unifiedRepo.saveAndFlush(orphan);

        MvcResult r = mvc.perform(get("/api/wallet/export").param("account", "Maybank"))
                .andExpect(status().isOk())
                .andReturn();
        String csv = r.getResponse().getContentAsString();
        assertTrue(csv.contains("MAYBANK SHOP"));
        assertFalse(csv.contains("UNIFIED ONLY ROW"));

        mvc.perform(get("/api/wallet/preview").param("account", "Maybank").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void previewWithoutFiltersShowsOnlyMyr() throws Exception {
        seed();
        mvc.perform(get("/api/wallet/preview").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void exportWithoutFiltersExcludesNonMyr() throws Exception {
        seed();
        MvcResult r = mvc.perform(get("/api/wallet/export"))
                .andExpect(status().isOk())
                .andReturn();
        String csv = r.getResponse().getContentAsString();
        assertEquals(4, csv.split("\n").length); // header + 3 MYR rows
        assertFalse(csv.contains("G. K. PIN"));
        assertFalse(csv.contains("BANGKOK NOODLE"));
    }

    @Test
    void idsExcludeNonMyr() throws Exception {
        seed();
        mvc.perform(get("/api/wallet/ids"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void exportByIdsDropsNonMyrSelection() throws Exception {
        seed();
        var all = repo.findAll();
        Long myrId = all.stream().filter(u -> "TNG MERCHANT".equals(u.getPayee())).findFirst().orElseThrow().getId();
        Long cnyId = all.stream().filter(u -> "G. K. PIN".equals(u.getPayee())).findFirst().orElseThrow().getId();
        MvcResult r = mvc.perform(get("/api/wallet/export").param("ids", myrId + "," + cnyId))
                .andExpect(status().isOk())
                .andReturn();
        String csv = r.getResponse().getContentAsString();
        String[] lines = csv.split("\n");
        assertEquals(2, lines.length); // header + the MYR row only
        assertTrue(lines[1].contains("TNG MERCHANT"));
    }

    @Test
    void exportByOnlyNonMyrIdsReturns400() throws Exception {
        seed();
        var all = repo.findAll();
        Long cnyId = all.stream().filter(u -> "G. K. PIN".equals(u.getPayee())).findFirst().orElseThrow().getId();
        Long thbId = all.stream().filter(u -> "BANGKOK NOODLE".equals(u.getPayee())).findFirst().orElseThrow().getId();
        mvc.perform(post("/api/wallet/export")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + cnyId + "," + thbId + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }
}
