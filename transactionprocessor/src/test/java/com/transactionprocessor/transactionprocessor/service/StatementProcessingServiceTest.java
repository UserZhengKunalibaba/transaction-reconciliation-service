package com.transactionprocessor.transactionprocessor.service;

import com.transactionprocessor.transactionprocessor.domain.SourceType;
import com.transactionprocessor.transactionprocessor.domain.UnifiedTransaction;
import com.transactionprocessor.transactionprocessor.parser.StatementFileDescriptor;
import com.transactionprocessor.transactionprocessor.repo.UnifiedTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full §38 duplicate/idempotency semantics through the real pipeline
 * (H2 database, no mocks for the processing path).
 */
@SpringBootTest
class StatementProcessingServiceTest {

    // Created eagerly in a static initializer (not @TempDir): DynamicPropertySource
    // runs during context startup, before JUnit would initialize temp dirs.
    static final Path uploadRoot;

    static {
        try {
            uploadRoot = Files.createTempDirectory("upload-pipeline-test");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Autowired
    StatementProcessingService processing;

    @Autowired
    UnifiedTransactionRepository unifiedRepo;

    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void uploadDir(DynamicPropertyRegistry registry) {
        registry.add("app.statement-upload-directory", () -> uploadRoot.toString());
    }

    @BeforeEach
    void clean() throws IOException {
        unifiedRepo.deleteAll();
        // Filesystem isolation: each test starts from an empty five-bucket layout.
        if (Files.isDirectory(uploadRoot)) {
            try (var s = Files.walk(uploadRoot)) {
                for (Path p : (Iterable<Path>) s.sorted(java.util.Comparator.reverseOrder())::iterator) {
                    if (!p.equals(uploadRoot)) Files.deleteIfExists(p);
                }
            }
        }
        for (String b : List.of("MAYBANK_SA", "MAYBANK_CC", "GRAB", "TNG", "ALIPAY", "WECHAT")) {
            Files.createDirectories(uploadRoot.resolve(b));
        }
    }

    private static UnifiedTransaction row(LocalDateTime ts, boolean dateOnly,
                                         String payee, String amount, SourceType source) {
        UnifiedTransaction u = new UnifiedTransaction();
        u.setTimestamp(ts);
        u.setDateOnly(dateOnly);
        u.setPayee(payee);
        u.setAmount(new BigDecimal(amount));
        u.setAmountCurrency("MYR");
        u.setSource(source);
        u.setType("EXPENSE");
        u.setDescription(payee + " " + amount);
        return u;
    }

    private static List<UnifiedTransaction> metroFile() {
        // Two identical date-only rows, as parsed from one statement in source order.
        return new ArrayList<>(List.of(
                row(LocalDateTime.of(2026, 9, 10, 0, 0), true, "METRO", "-2.00", SourceType.TNG),
                row(LocalDateTime.of(2026, 9, 10, 0, 0), true, "METRO", "-2.00", SourceType.TNG)));
    }

    @Test
    void sameTransactionTwiceInOneBatchInsertsOnce() {
        LocalDateTime ts = LocalDateTime.of(2026, 9, 10, 10, 0);
        FileImportResult r = processing.processTransactions("f.pdf", "TNG", new ArrayList<>(List.of(
                row(ts, false, "METRO", "-2.00", SourceType.TNG),
                row(ts, false, "METRO", "-2.00", SourceType.TNG))));
        assertEquals(2, r.getParsed());
        assertEquals(1, r.getInserted());
        assertEquals(1, r.getSkipped());
        assertEquals(1, unifiedRepo.count());
    }

    @Test
    void samePayeeAndAmountAtDifferentTimestampsAreTwoTransactions() {
        FileImportResult r = processing.processTransactions("f.pdf", "TNG", new ArrayList<>(List.of(
                row(LocalDateTime.of(2026, 9, 10, 10, 0), false, "METRO", "-2.00", SourceType.TNG),
                row(LocalDateTime.of(2026, 9, 10, 10, 5), false, "METRO", "-2.00", SourceType.TNG))));
        assertEquals(2, r.getInserted());
        assertEquals(2, unifiedRepo.count());
    }

    @Test
    void sameTimestampAndPayeeWithDifferentAmountsAreTwoTransactions() {
        LocalDateTime ts = LocalDateTime.of(2026, 9, 10, 10, 0);
        FileImportResult r = processing.processTransactions("f.pdf", "TNG", new ArrayList<>(List.of(
                row(ts, false, "METRO", "-2.00", SourceType.TNG),
                row(ts, false, "METRO", "-3.00", SourceType.TNG))));
        assertEquals(2, r.getInserted());
        assertEquals(2, unifiedRepo.count());
    }

    @Test
    void sameTimestampAndAmountWithDifferentPayeesAreTwoTransactions() {
        LocalDateTime ts = LocalDateTime.of(2026, 9, 10, 10, 0);
        FileImportResult r = processing.processTransactions("f.pdf", "TNG", new ArrayList<>(List.of(
                row(ts, false, "METRO", "-2.00", SourceType.TNG),
                row(ts, false, "COFFEE SHOP", "-2.00", SourceType.TNG))));
        assertEquals(2, r.getInserted());
        assertEquals(2, unifiedRepo.count());
    }

    @Test
    void identicalDateOnlyRowsBecomeDistinctTransactions() {
        FileImportResult r = processing.processTransactions("stmt.pdf", "TNG", metroFile());
        assertEquals(2, r.getParsed());
        assertEquals(2, r.getInserted());
        assertEquals(0, r.getSkipped());
        assertEquals(2, unifiedRepo.count());
        List<LocalDateTime> times = unifiedRepo.findAll().stream()
                .map(UnifiedTransaction::getTimestamp).sorted().toList();
        assertEquals(LocalDateTime.of(2026, 9, 10, 0, 0), times.get(0));
        assertEquals(LocalDateTime.of(2026, 9, 10, 0, 3), times.get(1));
    }

    @Test
    void reimportingDateOnlyFileSkipsEverything() {
        FileImportResult first = processing.processTransactions("stmt.pdf", "TNG", metroFile());
        assertEquals(2, first.getInserted());
        assertEquals(0, first.getSkipped());

        FileImportResult second = processing.processTransactions("stmt.pdf", "TNG", metroFile());
        assertEquals(0, second.getInserted());
        assertEquals(2, second.getSkipped());
        assertEquals(2, unifiedRepo.count());
    }

    @Test
    void sameTransactionInDifferentFilesIsSkippedSecondTime() {
        LocalDateTime ts = LocalDateTime.of(2026, 9, 10, 10, 0);
        processing.processTransactions("a.pdf", "TNG", new ArrayList<>(List.of(
                row(ts, false, "METRO", "-2.00", SourceType.TNG))));
        FileImportResult r = processing.processTransactions("b.pdf", "TNG", new ArrayList<>(List.of(
                row(ts, false, "METRO", "-2.00", SourceType.TNG))));
        assertEquals(0, r.getInserted());
        assertEquals(1, r.getSkipped());
        assertEquals(1, unifiedRepo.count());
    }

    @Test
    void sameTripleFromDifferentSourceIsDuplicate() {
        LocalDateTime ts = LocalDateTime.of(2026, 9, 10, 10, 0);
        processing.processTransactions("a.csv", "ALIPAY", new ArrayList<>(List.of(
                row(ts, false, "METRO", "2.00", SourceType.ALIPAY))));
        FileImportResult r = processing.processTransactions("b.xlsx", "WECHAT", new ArrayList<>(List.of(
                row(ts, false, "METRO", "2.00", SourceType.WECHATPAY))));
        assertEquals(0, r.getInserted());
        assertEquals(1, unifiedRepo.count());
    }

    @Test
    void scaleVariantsOfSameAmountAreDuplicates() {
        LocalDateTime ts = LocalDateTime.of(2026, 9, 10, 10, 0);
        processing.processTransactions("a.pdf", "TNG", new ArrayList<>(List.of(
                row(ts, false, "METRO", "-2", SourceType.TNG))));
        FileImportResult r = processing.processTransactions("b.pdf", "TNG", new ArrayList<>(List.of(
                row(ts, false, "METRO", "-2.00", SourceType.TNG))));
        assertEquals(0, r.getInserted());
        assertEquals(1, unifiedRepo.count());
    }

    @Test
    void databaseConstraintRejectsDuplicateTriple() {
        LocalDateTime ts = LocalDateTime.of(2026, 9, 10, 10, 0);
        unifiedRepo.saveAndFlush(row(ts, false, "METRO", "-2.00", SourceType.TNG));
        assertThrows(Exception.class, () ->
                unifiedRepo.saveAndFlush(row(ts, false, "METRO", "-2.00", SourceType.TNG)));
    }

    @Test
    void rowsWithoutParseableDateAreSkippedAndReported() {
        UnifiedTransaction undateable = row(null, false, "METRO", "-2.00", SourceType.TNG);
        UnifiedTransaction good = row(LocalDateTime.of(2026, 9, 10, 10, 0), false,
                "METRO", "-2.00", SourceType.TNG);
        FileImportResult r = processing.processTransactions("f.pdf", "TNG",
                new ArrayList<>(List.of(undateable, good)));
        assertEquals(1, r.getInserted());
        assertEquals(1, r.getSkipped());
        assertFalse(r.getErrors().isEmpty());
        assertEquals(1, unifiedRepo.count());
    }

    @Test
    void noIntermediatePersistence() {
        processing.processTransactions("f.pdf", "TNG", metroFile());
        assertEquals(2, count("unified_transaction"));
    }

    @Test
    void parserFailureLeavesNoRecords() throws Exception {
        Path bucket = Files.createDirectories(uploadRoot.resolve("TNG"));
        Path bad = bucket.resolve("20260910-aaaa.pdf");
        Files.write(bad, "this is not a pdf at all".getBytes());
        FileImportResult r = processing.processFile(
                new StatementFileDescriptor(bad, bad.getFileName().toString(), Files.size(bad), "PDF"));
        assertEquals(0, r.getInserted());
        assertFalse(r.getErrors().isEmpty());
        assertEquals(0, unifiedRepo.count());
    }

    @Test
    void sourceComesFromBucketNotParserGuessing() throws Exception {
        // Alipay-format content deliberately placed in the TNG bucket:
        // the bucket declares the source, the parser only reads the format.
        Path target = uploadRoot.resolve("TNG").resolve("20260910-alipay.csv");
        Files.copy(Path.of("src/test/resources/fixtures/alipay-sample.csv"),
                target, StandardCopyOption.REPLACE_EXISTING);
        FileImportResult r = processing.processFile(
                new StatementFileDescriptor(target, target.getFileName().toString(),
                        Files.size(target), "CSV"));
        assertEquals(3, r.getInserted());
        assertEquals(3, unifiedRepo.count());
        assertTrue(unifiedRepo.findAll().stream()
                .allMatch(u -> u.getSource() == SourceType.TNG));
    }

    @Test
    void endToEndWithRealFixtures() throws Exception {
        Path alipayTarget = uploadRoot.resolve("ALIPAY").resolve("20260910-alipay.csv");
        Path wechatTarget = uploadRoot.resolve("WECHAT").resolve("20260910-wechat.xlsx");
        Files.copy(Path.of("src/test/resources/fixtures/alipay-sample.csv"),
                alipayTarget, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(Path.of("src/test/resources/fixtures/wechat-sample.xlsx"),
                wechatTarget, StandardCopyOption.REPLACE_EXISTING);
        List<StatementFileDescriptor> files = List.of(
                new StatementFileDescriptor(alipayTarget, alipayTarget.getFileName().toString(),
                        Files.size(alipayTarget), "CSV"),
                new StatementFileDescriptor(wechatTarget, wechatTarget.getFileName().toString(),
                        Files.size(wechatTarget), "XLSX"));

        ImportSummary first = processing.processFiles(files);
        assertEquals(2, first.getFilesProcessed());
        assertEquals(5, first.getTransactionsParsed());
        assertEquals(5, first.getTransactionsInserted());
        assertEquals(0, first.getTransactionsSkipped());
        assertEquals(5, unifiedRepo.count());

        // Same files again: everything is an exact-match duplicate.
        ImportSummary second = processing.processFiles(files);
        assertEquals(5, second.getTransactionsParsed());
        assertEquals(0, second.getTransactionsInserted());
        assertEquals(5, second.getTransactionsSkipped());
        assertEquals(5, unifiedRepo.count());

        // Only unified_transaction persists.
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
