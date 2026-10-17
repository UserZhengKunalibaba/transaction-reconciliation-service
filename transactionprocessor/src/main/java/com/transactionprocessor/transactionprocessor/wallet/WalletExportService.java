package com.transactionprocessor.transactionprocessor.wallet;

import com.transactionprocessor.transactionprocessor.domain.ReconTransaction;
import com.transactionprocessor.transactionprocessor.domain.WalletAccount;
import com.transactionprocessor.transactionprocessor.repo.ReconTransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Stateless CSV export over the canonical {@code recon_transaction} table.
 *
 * <p>The export reads ONLY reconciled output: every row already sits in its
 * final WalletApp account (Maybank / Grab / Cash), including synthesized rows
 * such as Grab top-up incomes. It never touches {@code unified_transaction}.
 *
 * <p>No batch tracking, no on-disk files, no EXPORTED marking. Two export modes:
 * <ol>
 *   <li>Filter mode: caller filters by Wallet {@code account} + timestamp range
 *       and receives the matching rows.</li>
 *   <li>Selection mode: caller passes explicit recon row ids (picked from a
 *       filtered preview) and receives exactly those rows.</li>
 * </ol>
 * Both modes render rows ordered by timestamp ascending, the natural Wallet
 * import order.
 *
 * <p>MYR-only: the WalletApp CSV has no currency column, so every mode silently
 * drops non-MYR rows (CNY incomes, THB spends, …). Unmatched Alipay/WeChat
 * expenses are unaffected — reconciliation already stores them as
 * {@code -0.01 MYR} placeholders.
 */
@Service
public class WalletExportService {

    /** Hard cap on explicit id selections per export (URL/body abuse guard). */
    public static final int MAX_IDS = 5000;

    private final ReconTransactionRepository reconRepo;
    private final WalletCsvExporter csv;

    public WalletExportService(ReconTransactionRepository reconRepo, WalletCsvExporter csv) {
        this.reconRepo = reconRepo;
        this.csv = csv;
    }

    /** All MYR rows matching the filters, oldest first. Null/blank means "no constraint". */
    public List<ReconTransaction> findForExport(WalletAccount account, LocalDate from, LocalDate to) {
        List<ReconTransaction> rows = reconRepo.findAll(spec(account, from, to));
        rows.sort(Comparator.comparing(ReconTransaction::getTimestamp,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return rows;
    }

    /** Paginated preview with the same filter semantics, caller-supplied sort. */
    public Page<ReconTransaction> preview(WalletAccount account, LocalDate from, LocalDate to, Pageable pageable) {
        return reconRepo.findAll(spec(account, from, to), pageable);
    }

    /** Render the filtered rows as WalletApp CSV. Throws when nothing matches. */
    public String exportCsv(WalletAccount account, LocalDate from, LocalDate to) {
        List<ReconTransaction> rows = findForExport(account, from, to);
        if (rows.isEmpty()) throw new IllegalStateException("No reconciled transactions match the export filters");
        return csv.export(rows);
    }

    /** Ids of all rows matching the filters, oldest first. Used for "select all". */
    public List<Long> findIdsForExport(WalletAccount account, LocalDate from, LocalDate to) {
        return findForExport(account, from, to).stream()
                .map(ReconTransaction::getId)
                .toList();
    }

    /**
     * Render exactly the given recon rows as WalletApp CSV, oldest first.
     * Non-MYR rows in the selection are dropped (the CSV has no currency
     * column). Unknown ids are ignored; throws when nothing exportable remains.
     */
    public String exportCsvByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) throw new IllegalStateException("No rows selected for export");
        List<Long> distinct = ids.stream().filter(id -> id != null).distinct().toList();
        if (distinct.isEmpty()) throw new IllegalStateException("No rows selected for export");
        if (distinct.size() > MAX_IDS) {
            throw new IllegalArgumentException("Too many ids (max " + MAX_IDS + ")");
        }
        List<ReconTransaction> resolved = reconRepo.findAllById(distinct);
        if (resolved.isEmpty()) throw new IllegalStateException("Selected rows no longer exist");
        List<ReconTransaction> rows = resolved.stream()
                .filter(r -> r.getAmountCurrency() != null && r.getAmountCurrency().equalsIgnoreCase("MYR"))
                .sorted(Comparator.comparing(ReconTransaction::getTimestamp,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        if (rows.isEmpty()) throw new IllegalStateException("No selected rows are in MYR");
        return csv.export(rows);
    }

    /** Download filename, e.g. {@code wallet-Maybank-20260601-20260908.csv}. */
    public static String filename(WalletAccount account, LocalDate from, LocalDate to) {
        String a = account == null ? "all" : account.name();
        String f = from == null ? "start" : from.toString().replace("-", "");
        String t = to == null ? "now" : to.toString().replace("-", "");
        return "wallet-" + a + "-" + f + "-" + t + ".csv";
    }

    /** Download filename for an explicit selection, e.g. {@code wallet-selected-3rows.csv}. */
    public static String filenameForSelection(int count) {
        return "wallet-selected-" + count + "rows.csv";
    }

    private static Specification<ReconTransaction> spec(WalletAccount account, LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            // The CSV has no currency column: only MYR rows are exportable.
            ps.add(cb.equal(cb.upper(root.get("amountCurrency")), "MYR"));
            if (account != null) ps.add(cb.equal(root.get("account"), account));
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("timestamp"), from.atStartOfDay()));
            if (to != null) ps.add(cb.lessThan(root.get("timestamp"), to.plusDays(1).atStartOfDay()));
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }
}
