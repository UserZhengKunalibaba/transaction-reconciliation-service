package com.transactionprocessor.transactionprocessor.web;

import com.transactionprocessor.transactionprocessor.domain.ReconTransaction;
import com.transactionprocessor.transactionprocessor.domain.WalletAccount;
import com.transactionprocessor.transactionprocessor.recon.ReconciliationService;
import com.transactionprocessor.transactionprocessor.repo.ReconTransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilt recon API: trigger a run plus browse the single output table.
 *
 * <p>{@code POST /api/reconciliation/run} is intentionally non-transactional (see
 * {@link ReconciliationService}): each output row commits independently and retries
 * de-duplicate on {@code UNIQUE (source_unified_id, account)}.
 *
 * <p>{@code GET /api/reconciliation/transactions} exposes the whole output table with
 * timestamp + account filters and sorting.
 */
@RestController
@RequestMapping("/api/reconciliation")
public class ReconciliationController {

    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 200;

    private static final java.util.Set<String> ALLOWED_SORT_PROPERTIES = java.util.Set.of(
            "id", "timestamp", "account", "amount", "amountCurrency", "source",
            "payee", "type", "category", "description", "reference", "createdAt");

    private final ReconciliationService recon;
    private final ReconTransactionRepository reconRepo;
    private final EntityManager em;

    public ReconciliationController(ReconciliationService recon,
                                    ReconTransactionRepository reconRepo,
                                    EntityManager em) {
        this.recon = recon;
        this.reconRepo = reconRepo;
        this.em = em;
    }

    @PostMapping("/run")
    public Map<String, Object> run() {
        ReconciliationService.ReconResult r = recon.run();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("maybankCopied", r.maybankCopied);
        m.put("maybankNetted", r.maybankNetted);
        m.put("selfTransferPairs", r.selfTransferPairs);
        m.put("selfTransferMirrors", r.selfTransferMirrors);
        m.put("selfTransferRevoked", r.selfTransferRevoked);
        m.put("grabCopied", r.grabCopied);
        m.put("grabTopupIncome", r.grabTopupIncome);
        m.put("cashCopied", r.cashCopied);
        m.put("cashMatched", r.cashMatched);
        m.put("cashPlaceholder", r.cashPlaceholder);
        m.put("inserted", r.inserted);
        m.put("skipped", r.skipped);
        return m;
    }

    /**
     * Entire output table: optional account + timestamp-range filters, sortable.
     * {@code from}/{@code to} are inclusive calendar dates applied to the timestamp.
     */
    @GetMapping("/transactions")
    public Page<ReconTransaction> transactions(
            @RequestParam(required = false) WalletAccount account,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = DEFAULT_SIZE, sort = "timestamp", direction = Sort.Direction.DESC) Pageable pageable) {
        Specification<ReconTransaction> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (account != null) ps.add(cb.equal(root.get("account"), account));
            if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("timestamp"), from.atStartOfDay()));
            if (to != null) ps.add(cb.lessThan(root.get("timestamp"), to.plusDays(1).atStartOfDay()));
            return cb.and(ps.toArray(new Predicate[0]));
        };
        return reconRepo.findAll(spec, sanitize(pageable));
    }

    /**
     * Reconciled total of {@code amount} over ALL rows matching the current filters,
     * ignoring pagination. Shares the exact filter semantics of {@link #transactions}.
     *
     * <p>Response: {@code {"count": n, "subtotal": sum, "byCurrency": {CUR: sum}}}.
     * Rows mix currencies (e.g. MYR + THB), so the plain {@code subtotal} mixes
     * them too — use {@code byCurrency} when that matters.
     */
    @GetMapping("/transactions/subtotal")
    @Transactional(readOnly = true)
    public Map<String, Object> subtotal(
            @RequestParam(required = false) WalletAccount account,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<ReconTransaction> countRoot = countQuery.from(ReconTransaction.class);
        countQuery.select(cb.count(countRoot))
                .where(reconPredicates(countRoot, cb, account, from, to).toArray(new Predicate[0]));
        long count = em.createQuery(countQuery).getSingleResult();

        CriteriaQuery<BigDecimal> sumQuery = cb.createQuery(BigDecimal.class);
        Root<ReconTransaction> sumRoot = sumQuery.from(ReconTransaction.class);
        sumQuery.select(cb.sum(sumRoot.get("amount")))
                .where(reconPredicates(sumRoot, cb, account, from, to).toArray(new Predicate[0]));
        BigDecimal total = em.createQuery(sumQuery).getSingleResult();
        if (total == null) {
            total = BigDecimal.ZERO;
        }

        CriteriaQuery<Tuple> groupQuery = cb.createTupleQuery();
        Root<ReconTransaction> groupRoot = groupQuery.from(ReconTransaction.class);
        groupQuery.multiselect(groupRoot.get("amountCurrency"), cb.sum(groupRoot.get("amount")))
                .where(reconPredicates(groupRoot, cb, account, from, to).toArray(new Predicate[0]))
                .groupBy(groupRoot.get("amountCurrency"));
        Map<String, BigDecimal> byCurrency = new LinkedHashMap<>();
        for (Tuple tuple : em.createQuery(groupQuery).getResultList()) {
            byCurrency.put(tuple.get(0, String.class), tuple.get(1, BigDecimal.class));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", count);
        result.put("subtotal", total);
        result.put("byCurrency", byCurrency);
        return result;
    }

    private static List<Predicate> reconPredicates(
            Root<ReconTransaction> root, CriteriaBuilder cb,
            WalletAccount account, LocalDate from, LocalDate to) {
        List<Predicate> ps = new ArrayList<>();
        if (account != null) ps.add(cb.equal(root.get("account"), account));
        if (from != null) ps.add(cb.greaterThanOrEqualTo(root.get("timestamp"), from.atStartOfDay()));
        if (to != null) ps.add(cb.lessThan(root.get("timestamp"), to.plusDays(1).atStartOfDay()));
        return ps;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", reconRepo.count());
        for (WalletAccount a : WalletAccount.values()) {
            m.put(a.name(), reconRepo.countByAccount(a));
        }
        return m;
    }

    /** Wipe the output table (e.g. before a clean re-run). Single-statement delete. */
    @DeleteMapping("/transactions/all")
    @Transactional
    public Map<String, Object> clear() {
        long n = reconRepo.count();
        reconRepo.deleteAllInBatch();
        return Map.of("deleted", n);
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
                ? Sort.by(Sort.Direction.DESC, "timestamp")
                : Sort.by(orders);
        return PageRequest.of(page, size, sort);
    }
}
