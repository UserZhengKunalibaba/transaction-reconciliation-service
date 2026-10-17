package com.transactionprocessor.transactionprocessor.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * The single canonical transaction schema for the entire application.
 *
 * <p>This is the ONLY table the statement processing pipeline persists. Parser output
 * ({@code RawTransactionData}) and any intermediate normalized objects are volatile:
 * they exist only in memory during a processing operation and are never saved.
 *
 * <p>Deduplication key (exact match on canonical stored values):
 * {@code timestamp + payee + amount}. Source is deliberately NOT part of the key.
 *
 * <p>Timestamp semantics: sources with a real transaction time keep it; date-only
 * sources receive deterministic synthetic times (midnight + row order × step) assigned
 * centrally during processing, so reprocessing identical input reproduces identical keys.
 */
@Entity
@Table(name = "unified_transaction",
        uniqueConstraints = @UniqueConstraint(name = "uq_unified_txn_key",
                columnNames = {"txn_timestamp", "payee", "amount"}),
        indexes = {
                @Index(name = "idx_unified_txn_timestamp", columnList = "txn_timestamp"),
                @Index(name = "idx_unified_txn_source", columnList = "source")
        })
public class UnifiedTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Canonical timestamp: real source time, or deterministic synthetic time for date-only rows. */
    @Column(name = "txn_timestamp", nullable = false)
    private LocalDateTime timestamp;

    /** Canonical payee: trimmed, whitespace-collapsed, upper-cased merchant. Exact match, no fuzzing. */
    @Column(nullable = false, length = 256)
    private String payee;

    /**
     * Canonical amount: the resolved MYR amount when the normalizer produced one,
     * otherwise the original amount. Scale-insensitive for comparison
     * ({@code 2}, {@code 2.0}, {@code 2.00} are the same monetary amount).
     */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    /** Currency of {@link #amount}: {@code MYR} when resolved, otherwise the original currency. */
    @Column(nullable = false, length = 8)
    private String amountCurrency;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private SourceType source;

    @Column(length = 256)
    private String reference;

    /** EXPENSE, INCOME, TRANSFER. */
    @Column(length = 16)
    private String type;

    @Column(length = 16)
    @Enumerated(EnumType.STRING)
    private WalletAccount account;

    @Column(length = 64)
    private String category;

    @Column(length = 1024)
    private String description;

    @Column(precision = 14, scale = 2)
    private BigDecimal originalAmount;

    @Column(length = 8)
    private String originalCurrency;

    private Instant createdAt = Instant.now();

    /**
     * Not persisted. True when the source row carried only a date, so the pipeline
     * must assign a deterministic synthetic time before deduplication/insert.
     */
    @Transient
    private boolean dateOnly;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime v) { this.timestamp = v; }
    public String getPayee() { return payee; }
    public void setPayee(String v) { this.payee = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public String getAmountCurrency() { return amountCurrency; }
    public void setAmountCurrency(String v) { this.amountCurrency = v; }
    public SourceType getSource() { return source; }
    public void setSource(SourceType v) { this.source = v; }
    public String getReference() { return reference; }
    public void setReference(String v) { this.reference = v; }
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public WalletAccount getAccount() { return account; }
    public void setAccount(WalletAccount v) { this.account = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public BigDecimal getOriginalAmount() { return originalAmount; }
    public void setOriginalAmount(BigDecimal v) { this.originalAmount = v; }
    public String getOriginalCurrency() { return originalCurrency; }
    public void setOriginalCurrency(String v) { this.originalCurrency = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public boolean isDateOnly() { return dateOnly; }
    public void setDateOnly(boolean v) { this.dateOnly = v; }
}
