package com.transactionprocessor.transactionprocessor.web;

import com.transactionprocessor.transactionprocessor.domain.ReconTransaction;
import com.transactionprocessor.transactionprocessor.domain.WalletAccount;
import com.transactionprocessor.transactionprocessor.wallet.WalletExportService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stateless WalletApp CSV export over {@code recon_transaction}.
 *
 * <ul>
 *   <li>{@code GET /api/wallet/preview} pages the filtered rows so the UI can
 *       show what a download will contain.</li>
 *   <li>{@code GET /api/wallet/ids} returns the ids of all filtered rows
 *       (oldest first) so the UI can "select all matching".</li>
 *   <li>{@code GET /api/wallet/export} streams CSV: filter mode
 *       ({@code ?account=&from=&to=}) or selection mode ({@code ?ids=1,2,3},
 *       which wins when present).</li>
 *   <li>{@code POST /api/wallet/export} streams CSV for large selections that
 *       do not fit in a query string: JSON body {@code {"ids": [...]}}.</li>
 * </ul>
 *
 * <p>Every endpoint reads reconciled output only. {@code unified_transaction}
 * is never touched here.
 */
@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 200;

    private static final Set<String> ALLOWED_SORT_PROPERTIES = Set.of(
            "id", "timestamp", "account", "amount", "amountCurrency", "source",
            "payee", "type", "category", "description", "reference",
            "originalAmount", "originalCurrency", "createdAt");

    private final WalletExportService exportService;

    public WalletController(WalletExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping("/preview")
    public Page<ReconTransaction> preview(
            @RequestParam(required = false) WalletAccount account,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = DEFAULT_SIZE, sort = "timestamp", direction = Sort.Direction.ASC) Pageable pageable) {
        return exportService.preview(account, from, to, sanitize(pageable));
    }

    @GetMapping("/ids")
    public List<Long> ids(
            @RequestParam(required = false) WalletAccount account,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return exportService.findIdsForExport(account, from, to);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) WalletAccount account,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) List<Long> ids) {
        try {
            if (ids != null) {
                String csv = exportService.exportCsvByIds(ids);
                return csvDownload(csv, WalletExportService.filenameForSelection(ids.size()));
            }
            String csv = exportService.exportCsv(account, from, to);
            return csvDownload(csv, WalletExportService.filename(account, from, to));
        } catch (IllegalStateException e) {
            throw new ExportEmptyException(e.getMessage());
        }
    }

    /** Batch export for selections too large for a query string. */
    @PostMapping("/export")
    public ResponseEntity<byte[]> exportPost(@RequestBody ExportSelectionRequest body) {
        try {
            List<Long> ids = body == null ? null : body.ids();
            if (ids != null) {
                String csv = exportService.exportCsvByIds(ids);
                return csvDownload(csv, WalletExportService.filenameForSelection(ids.size()));
            }
            WalletAccount account = null;
            LocalDate from = null;
            LocalDate to = null;
            if (body != null) {
                if (body.account() != null && !body.account().isBlank()) {
                    account = WalletAccount.valueOf(body.account().trim());
                }
                if (body.from() != null && !body.from().isBlank()) {
                    from = LocalDate.parse(body.from().trim());
                }
                if (body.to() != null && !body.to().isBlank()) {
                    to = LocalDate.parse(body.to().trim());
                }
            }
            String csv = exportService.exportCsv(account, from, to);
            return csvDownload(csv, WalletExportService.filename(account, from, to));
        } catch (IllegalStateException e) {
            throw new ExportEmptyException(e.getMessage());
        }
    }

    /** Explicit ids ({@code {"ids": [1,2,3]}}) win; otherwise filter fields apply. */
    public record ExportSelectionRequest(List<Long> ids, String account, String from, String to) {
    }

    private static ResponseEntity<byte[]> csvDownload(String csv, String filename) {
        byte[] body = csv.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .contentLength(body.length)
                .body(body);
    }

    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    static class ExportEmptyException extends RuntimeException {
        ExportEmptyException(String message) { super(message); }
    }

    @ExceptionHandler(ExportEmptyException.class)
    public ResponseEntity<Map<String, String>> handleEmpty(ExportEmptyException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    private static Pageable sanitize(Pageable pageable) {
        int page = pageable.getPageNumber() < 0 ? 0 : pageable.getPageNumber();
        int size = pageable.getPageSize() <= 0 ? DEFAULT_SIZE : Math.min(pageable.getPageSize(), MAX_SIZE);
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order order : pageable.getSort()) {
            if (order.getProperty() != null && ALLOWED_SORT_PROPERTIES.contains(order.getProperty())) {
                orders.add(order);
            }
        }
        Sort sort = orders.isEmpty()
                ? Sort.by(Sort.Direction.ASC, "timestamp")
                : Sort.by(orders);
        return PageRequest.of(page, size, sort);
    }
}
