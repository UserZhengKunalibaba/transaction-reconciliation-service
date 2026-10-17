package com.transactionprocessor.transactionprocessor.service;

import com.transactionprocessor.transactionprocessor.config.AppConfig;
import com.transactionprocessor.transactionprocessor.domain.SourceType;
import com.transactionprocessor.transactionprocessor.domain.UnifiedTransaction;
import com.transactionprocessor.transactionprocessor.normalizer.NormalizerService;
import com.transactionprocessor.transactionprocessor.parser.ParserRegistry;
import com.transactionprocessor.transactionprocessor.parser.RawTransactionData;
import com.transactionprocessor.transactionprocessor.parser.StatementFileDescriptor;
import com.transactionprocessor.transactionprocessor.parser.StatementParseException;
import com.transactionprocessor.transactionprocessor.parser.StatementParser;
import com.transactionprocessor.transactionprocessor.repo.UnifiedTransactionBatchWriter;
import com.transactionprocessor.transactionprocessor.repo.UnifiedTransactionRepository;
import com.transactionprocessor.transactionprocessor.upload.StatementBucket;
import com.transactionprocessor.transactionprocessor.util.MaskingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Stateless statement processing pipeline:
 *
 * <pre>
 * upload event → parser → volatile parsed data → normalizer → UnifiedTransaction
 *     → batch deduplication → single batch INSERT → DONE
 * </pre>
 *
 * <p>Only {@code unified_transaction} rows survive. Everything before the final
 * insert is volatile in-memory state. No file/import history, no state machine,
 * no reconciliation/matching/wallet side effects — the pipeline stops immediately
 * after the batch insert.
 *
 * <p>Entry point is {@link #processFiles(List)} with explicit file descriptors
 * resolved by the upload listener. There are no manual scan/import endpoints.
 */
@Service
public class StatementProcessingService {
    private static final Logger log = LoggerFactory.getLogger(StatementProcessingService.class);

    /** Dedup/update window chunk size: bounds the batch lookup and INSERT batches. */
    private static final int CHUNK_SIZE = 1000;

    /** Synthetic-time cap: rows beyond this share 23:57 rather than spilling to the next day. */
    private static final int MAX_SYNTHETIC_MINUTES = 23 * 60 + 57;

    /** Exact-match deduplication key on canonical stored values. Source is NOT part of the key. */
    record DedupKey(LocalDateTime timestamp, String payee, String amountPlain) {
        static DedupKey of(UnifiedTransaction u) {
            return new DedupKey(u.getTimestamp(), u.getPayee(),
                    NormalizerService.dedupAmountPlain(u.getAmount()));
        }
    }

    private final AppConfig config;
    private final ParserRegistry registry;
    private final NormalizerService normalizer;
    private final UnifiedTransactionRepository unifiedRepo;
    private final UnifiedTransactionBatchWriter batchWriter;

    public StatementProcessingService(AppConfig config,
                                      ParserRegistry registry, NormalizerService normalizer,
                                      UnifiedTransactionRepository unifiedRepo,
                                      UnifiedTransactionBatchWriter batchWriter) {
        this.config = config;
        this.registry = registry;
        this.normalizer = normalizer;
        this.unifiedRepo = unifiedRepo;
        this.batchWriter = batchWriter;
    }

    // ---- public API ----

    /**
     * Parse + normalize + deduplicate + insert exactly the given files.
     * Called by the asynchronous upload listener with the just-uploaded files.
     */
    public ImportSummary processFiles(List<StatementFileDescriptor> files) {
        List<FileImportResult> results = new ArrayList<>();
        for (StatementFileDescriptor f : files) {
            results.add(processFile(f));
        }
        return summarize(results);
    }

    // ---- pipeline stages (no transaction spans these; persistence is one batch write) ----

    FileImportResult processFile(StatementFileDescriptor desc) {
        String bucket = bucketOf(desc.getPath());
        log.info("Processing {} (bucket {})", MaskingUtil.mask(desc.getFilename()), bucket);
        Path effectivePath = desc.getPath();
        Path tempExtracted = null;
        List<String> errors = new ArrayList<>();
        try {
            String fileType = desc.getFileType();
            String reportName = desc.getFilename();
            if ("ZIP".equals(fileType)) {
                tempExtracted = extractFirstSupported(desc.getPath());
                if (tempExtracted == null) {
                    errors.add("ZIP contains no supported statement files");
                    return new FileImportResult(reportName, bucket, 0, 0, 0, errors);
                }
                String member = tempExtracted.getFileName().toString();
                effectivePath = tempExtracted;
                fileType = typeOf(member);
                reportName = desc.getFilename() + "!" + member;
            }
            StatementFileDescriptor effective =
                    new StatementFileDescriptor(effectivePath, reportName, 0, fileType);
            StatementParser parser = registry.find(effective);
            if (parser == null) {
                errors.add("No parser supports file: " + desc.getFilename());
                return new FileImportResult(reportName, bucket, 0, 0, 0, errors);
            }
            // Source is declared by the bucket, not guessed by the parser: each bucket
            // assigns a different enum. The registry is only a fallback for files
            // outside the six buckets (e.g. stray files directly under the root).
            SourceType source = sourceForBucket(bucket);
            if (source == null) {
                source = registry.sourceOf(parser, effective);
                log.info("File {} is outside any bucket; source fell back to parser: {}",
                        MaskingUtil.mask(desc.getFilename()), source);
            }
            List<RawTransactionData> parsed;
            try {
                parsed = parser.parse(effective);
            } catch (StatementParseException e) {
                // Parser failure: nothing was written (there is no intermediate state
                // to clean up). Report and move on.
                String msg = "[" + e.getCode() + "] " + MaskingUtil.mask(e.getMessage());
                log.warn("Parser failed for {}: {}", MaskingUtil.mask(desc.getFilename()), msg);
                errors.add(msg);
                return new FileImportResult(reportName, bucket, 0, 0, 0, errors);
            }
            List<UnifiedTransaction> unified = new ArrayList<>();
            for (RawTransactionData d : parsed) {
                unified.add(normalizer.normalizeToUnified(d, source));
            }
            log.info("Parsed {} transactions from {}", parsed.size(), MaskingUtil.mask(desc.getFilename()));
            FileImportResult r = processTransactions(reportName, bucket, unified);
            r.getErrors().addAll(0, errors);
            return r;
        } catch (Exception e) {
            log.warn("Failed to process {}: {}", MaskingUtil.mask(desc.getFilename()), e.getMessage());
            errors.add("failed to process file: " + e.getMessage());
            return new FileImportResult(desc.getFilename(), bucket, 0, 0, 0, errors);
        } finally {
            if (tempExtracted != null) {
                try {
                    Files.deleteIfExists(tempExtracted);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Normalize-stage output → synthetic timestamps → deduplication → batch insert.
     * Public seam for tests: the full §38 duplicate/idempotency semantics are
     * verifiable here without PDF fixtures. The list order is the source row order.
     */
    public FileImportResult processTransactions(String filename, String bucket,
                                               List<UnifiedTransaction> rows) {
        List<String> errors = new ArrayList<>();
        assignSyntheticTimestamps(rows, config.getSyntheticTimestampStepMinutes(), errors);

        List<UnifiedTransaction> datable = new ArrayList<>();
        int undateable = 0;
        for (UnifiedTransaction u : rows) {
            if (u.getTimestamp() == null) {
                undateable++;
            } else {
                datable.add(u);
            }
        }
        if (undateable > 0) {
            errors.add(undateable + " row(s) without a parseable date were skipped");
        }

        // In-batch dedup first (keep first occurrence in source order), then against the DB.
        Map<DedupKey, UnifiedTransaction> unique = new LinkedHashMap<>();
        for (UnifiedTransaction u : datable) {
            unique.putIfAbsent(DedupKey.of(u), u);
        }
        List<UnifiedTransaction> batch = new ArrayList<>(unique.values());
        List<UnifiedTransaction> fresh = subtractExisting(batch);
        int inserted = insertBatches(filename, fresh);
        int skipped = rows.size() - inserted;
        log.info("Normalized {} transactions from {}; inserted {}, skipped {} duplicates",
                rows.size(), MaskingUtil.mask(filename), inserted, skipped);
        return new FileImportResult(filename, bucket, rows.size(), inserted, skipped, errors);
    }

    /**
     * Central synthetic-time assignment. Date-only rows of one file, in source row
     * order, become {@code date at 00:00 + n × stepMinutes}. Deterministic per input:
     * reprocessing identical rows reproduces identical timestamps, which is what makes
     * timestamp-based deduplication idempotent. Rows with a real time are untouched.
     */
    static void assignSyntheticTimestamps(List<UnifiedTransaction> rows, int stepMinutes,
                                         List<String> errors) {
        int step = stepMinutes <= 0 ? 3 : stepMinutes;
        int dateOnlyIndex = 0;
        for (UnifiedTransaction u : rows) {
            if (u.getTimestamp() == null || !u.isDateOnly()) {
                continue;
            }
            int minutes = Math.min(dateOnlyIndex * step, MAX_SYNTHETIC_MINUTES);
            u.setTimestamp(u.getTimestamp().toLocalDate().atStartOfDay().plusMinutes(minutes));
            u.setDateOnly(false);
            dateOnlyIndex++;
        }
    }

    /** Batch-oriented dedup: one candidate query per chunk, triple matching in memory. */
    private List<UnifiedTransaction> subtractExisting(List<UnifiedTransaction> batch) {
        List<UnifiedTransaction> fresh = new ArrayList<>();
        for (int i = 0; i < batch.size(); i += CHUNK_SIZE) {
            List<UnifiedTransaction> chunk = batch.subList(i, Math.min(batch.size(), i + CHUNK_SIZE));
            LocalDateTime min = null, max = null;
            List<String> payees = new ArrayList<>();
            for (UnifiedTransaction u : chunk) {
                if (min == null || u.getTimestamp().isBefore(min)) min = u.getTimestamp();
                if (max == null || u.getTimestamp().isAfter(max)) max = u.getTimestamp();
                payees.add(u.getPayee());
            }
            java.util.Set<DedupKey> existing = new java.util.HashSet<>();
            for (UnifiedTransaction u : unifiedRepo.findDedupCandidates(min, max, payees)) {
                existing.add(DedupKey.of(u));
            }
            for (UnifiedTransaction u : chunk) {
                if (!existing.contains(DedupKey.of(u))) {
                    fresh.add(u);
                }
            }
        }
        return fresh;
    }

    /**
     * Single batch INSERT per chunk. On a unique-constraint race (concurrent import
     * inserting the same key between our lookup and our write), the transaction rolls
     * back leaving the DB unchanged; we re-check once and insert only survivors.
     */
    private int insertBatches(String filename, List<UnifiedTransaction> fresh) {
        int inserted = 0;
        for (int i = 0; i < fresh.size(); i += CHUNK_SIZE) {
            List<UnifiedTransaction> chunk = fresh.subList(i, Math.min(fresh.size(), i + CHUNK_SIZE));
            try {
                batchWriter.batchInsert(chunk);
                inserted += chunk.size();
            } catch (DataIntegrityViolationException race) {
                log.info("Concurrent insert detected for {}; re-checking {} rows",
                        MaskingUtil.mask(filename), chunk.size());
                List<UnifiedTransaction> survivors = subtractExisting(new ArrayList<>(chunk));
                if (!survivors.isEmpty()) {
                    batchWriter.batchInsert(survivors);
                    inserted += survivors.size();
                }
            }
        }
        return inserted;
    }

    private ImportSummary summarize(List<FileImportResult> results) {
        int parsed = 0, inserted = 0, skipped = 0;
        for (FileImportResult r : results) {
            parsed += r.getParsed();
            inserted += r.getInserted();
            skipped += r.getSkipped();
        }
        return new ImportSummary(results.size(), parsed, inserted, skipped, results);
    }

    /** Bucket = first path segment under the upload root (the six bucket directories). */
    String bucketOf(Path file) {
        try {
            Path root = Paths.get(config.getStatementUploadDirectory()).toAbsolutePath().normalize();
            Path rel = root.relativize(file.toAbsolutePath().normalize());
            if (rel.getNameCount() >= 2) {
                return rel.getName(0).toString();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /**
     * Source declared by the bucket. Returns {@code null} when the file sits outside
     * the six buckets, letting the caller fall back to parser-based detection.
     */
    static SourceType sourceForBucket(String bucket) {
        try {
            return StatementBucket.fromString(bucket).toSourceType();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Path extractFirstSupported(Path zip) throws Exception {
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName().toLowerCase();
                if (n.endsWith(".pdf") || n.endsWith(".csv") || n.endsWith(".xlsx") || n.endsWith(".xls")) {
                    Path tmp = Files.createTempFile("stmt-", "-" + Paths.get(e.getName()).getFileName().toString());
                    try (var in = zf.getInputStream(e)) {
                        Files.write(tmp, in.readAllBytes());
                    }
                    return tmp;
                }
            }
        }
        return null;
    }

    private String typeOf(String filename) {
        String l = filename.toLowerCase();
        if (l.endsWith(".pdf")) return "PDF";
        if (l.endsWith(".csv")) return "CSV";
        if (l.endsWith(".xlsx")) return "XLSX";
        if (l.endsWith(".xls")) return "XLS";
        return "UNKNOWN";
    }
}
