package com.transactionprocessor.transactionprocessor.web;

import com.transactionprocessor.transactionprocessor.domain.SourceType;
import com.transactionprocessor.transactionprocessor.domain.UnifiedTransaction;
import com.transactionprocessor.transactionprocessor.domain.WalletAccount;
import com.transactionprocessor.transactionprocessor.repo.UnifiedTransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Paginated view over the {@code unified_transaction} table, plus row deletion
 * and a filter-aware subtotal.
 *
 * <p>Reads support filtering/sorting/pagination. The subtotal aggregates
 * {@code amount} over ALL rows matching the current filters (ignoring
 * pagination). Writes are deletes only: single-row, batch by ids, or
 * clean-the-table. No detail joins, no raw/match lookups.
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 200;

    /**
     * Entity properties clients may sort by (via Spring's {@code sort=prop,dir}).
     * Anything else falls back to {@code timestamp desc} instead of 500ing on
     * an unknown property (e.g. the transient {@code dateOnly} flag).
     */
    private static final java.util.Set<String> ALLOWED_SORT_PROPERTIES = java.util.Set.of(
            "id", "timestamp", "payee", "amount", "amountCurrency", "source",
            "reference", "type", "account", "category", "description",
            "originalAmount", "originalCurrency", "createdAt");

    private final UnifiedTransactionRepository repo;
    private final EntityManager em;

    public TransactionController(UnifiedTransactionRepository repo, EntityManager em) {
        this.repo = repo;
        this.em = em;
    }

    @GetMapping
    public Page<UnifiedTransaction> list(
            @RequestParam(required = false) SourceType source,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) WalletAccount account,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String payee,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = DEFAULT_SIZE, sort = "timestamp", direction = Sort.Direction.DESC) Pageable pageable) {
        TransactionFilter filter = TransactionFilter.of(source, type, account, category, payee, from, to);
        Pageable safe = sanitize(pageable);

        Specification<UnifiedTransaction> spec =
                (root, query, cb) -> cb.and(filterPredicates(root, cb, filter).toArray(new Predicate[0]));
        return repo.findAll(spec, safe);
    }

    /**
     * Subtotal of {@code amount} over ALL rows matching the current filters,
     * ignoring pagination. Shares the exact filter semantics of {@link #list}.
     *
     * <p>Response: {@code {"count": n, "subtotal": sum, "byCurrency": {CUR: sum}}}.
     * Rows mix currencies (e.g. MYR + CNY), so the plain {@code subtotal} mixes
     * them too — use {@code byCurrency} when that matters.
     */
    @GetMapping("/subtotal")
    @Transactional(readOnly = true)
    public Map<String, Object> subtotal(
            @RequestParam(required = false) SourceType source,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) WalletAccount account,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String payee,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        TransactionFilter filter = TransactionFilter.of(source, type, account, category, payee, from, to);
        CriteriaBuilder cb = em.getCriteriaBuilder();

        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<UnifiedTransaction> countRoot = countQuery.from(UnifiedTransaction.class);
        countQuery.select(cb.count(countRoot))
                .where(filterPredicates(countRoot, cb, filter).toArray(new Predicate[0]));
        long count = em.createQuery(countQuery).getSingleResult();

        CriteriaQuery<BigDecimal> sumQuery = cb.createQuery(BigDecimal.class);
        Root<UnifiedTransaction> sumRoot = sumQuery.from(UnifiedTransaction.class);
        sumQuery.select(cb.sum(sumRoot.get("amount")))
                .where(filterPredicates(sumRoot, cb, filter).toArray(new Predicate[0]));
        BigDecimal total = em.createQuery(sumQuery).getSingleResult();
        if (total == null) {
            total = BigDecimal.ZERO;
        }

        CriteriaQuery<Tuple> groupQuery = cb.createTupleQuery();
        Root<UnifiedTransaction> groupRoot = groupQuery.from(UnifiedTransaction.class);
        groupQuery.multiselect(groupRoot.get("amountCurrency"), cb.sum(groupRoot.get("amount")))
                .where(filterPredicates(groupRoot, cb, filter).toArray(new Predicate[0]))
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

    @GetMapping("/{id}")
    public UnifiedTransaction getOne(@PathVariable Long id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("not found"));
    }

    /** Delete one row. 400 when the id does not exist. */
    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Object> deleteOne(@PathVariable Long id) {
        if (!repo.existsById(id)) {
            throw new IllegalArgumentException("not found: " + id);
        }
        repo.deleteById(id);
        return Map.of("deleted", 1);
    }

    /**
     * Batch delete by ids, e.g. {@code DELETE /api/transactions?ids=1,2,3}.
     * Missing ids are ignored; the response reports how many rows were removed.
     */
    @DeleteMapping
    @Transactional
    public Map<String, Object> deleteBatch(@RequestParam List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("ids is required");
        }
        if (ids.size() > 1000) {
            throw new IllegalArgumentException("too many ids (max 1000)");
        }
        List<Long> existing = repo.findAllById(ids).stream()
                .map(UnifiedTransaction::getId)
                .toList();
        if (!existing.isEmpty()) {
            repo.deleteAllByIdInBatch(existing);
        }
        return Map.of("deleted", existing.size());
    }

    /** Delete every row in the table. Reports how many rows were removed. */
    @DeleteMapping("/all")
    @Transactional
    public Map<String, Object> deleteAll() {
        long count = repo.count();
        repo.deleteAllInBatch();
        return Map.of("deleted", count);
    }

    /** Normalized list/subtotal filters. Null means "no constraint". */
    private record TransactionFilter(
            SourceType source, String type, WalletAccount account, String category,
            String payee, LocalDateTime from, LocalDateTime toExclusive) {
        static TransactionFilter of(SourceType source, String type, WalletAccount account,
                                    String category, String payee, LocalDate from, LocalDate to) {
            return new TransactionFilter(
                    source,
                    blankToNull(type) == null ? null : type.trim().toUpperCase(),
                    account,
                    blankToNull(category) == null ? null : category.trim(),
                    blankToNull(payee) == null ? null : payee.trim(),
                    from == null ? null : from.atStartOfDay(),
                    to == null ? null : toExclusiveBound(to));
        }
    }

    /**
     * Exclusive upper bound for a date filter: start of the next day. Package
     * visible for tests. Must stay exactly representable (no 999999999 nanos).
     */
    static LocalDateTime toExclusiveBound(LocalDate to) {
        return to.plusDays(1).atStartOfDay();
    }

    /** Shared predicate builder so list and subtotal filter identically. */
    private static List<Predicate> filterPredicates(
            Root<UnifiedTransaction> root, CriteriaBuilder cb, TransactionFilter filter) {
        List<Predicate> predicates = new ArrayList<>();
        if (filter.source() != null) {
            predicates.add(cb.equal(root.get("source"), filter.source()));
        }
        if (filter.type() != null) {
            predicates.add(cb.equal(root.get("type"), filter.type()));
        }
        if (filter.account() != null) {
            predicates.add(cb.equal(root.get("account"), filter.account()));
        }
        if (filter.category() != null) {
            predicates.add(cb.equal(root.get("category"), filter.category()));
        }
        if (filter.payee() != null) {
            // LIKE pattern built in Java so no null/unknown-type param is ever
            // bound inside upper()/concat() (Postgres: upper(bytea) error).
            String pattern = "%" + escapeLike(filter.payee()).toUpperCase() + "%";
            predicates.add(cb.like(cb.upper(root.get("payee")), pattern, '\\'));
        }
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("timestamp"), filter.from()));
            }
            if (filter.toExclusive() != null) {
                predicates.add(cb.lessThan(root.get("timestamp"), filter.toExclusive()));
            }
        return predicates;
    }

    private static String blankToNull(String s) {        return s == null || s.isBlank() ? null : s;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
