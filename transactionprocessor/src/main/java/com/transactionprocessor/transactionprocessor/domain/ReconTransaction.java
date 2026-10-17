package com.transactionprocessor.transactionprocessor.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Single output table for the rebuilt reconciliation module.
 *
 * <p>Input is {@code unified_transaction} (transaction records). Each run copies rows
 * into one of the three WalletApp accounts:
 * <ul>
 *   <li>Maybank = union of MAYBANK_SA + MAYBANK_CC (+ legacy MAYBANK), no special handling.</li>
 *   <li>Grab = all GRAB rows copied untouched + one INCOME row per Maybank→GrabPay
 *       top-up, sharing the exact source timestamp.</li>
 *   <li>Cash = faithful copy of every remaining source (TNG, ALIPAY, WECHATPAY).</li>
 * </ul>
 *
 * <p>No intermediate state, no state machine: candidates are built volatile in memory
 * and only these rows are persisted. Idempotency across retries comes from the
 * {@code (source_unified_id, account)} unique key — a Maybank top-up legitimately
 * produces two rows with the same source id but different accounts, while every
 * other input row produces exactly one.
 */
@Entity
@Table(name = "recon_transaction",
        uniqueConstraints = @UniqueConstraint(name = "uq_recon_source_account",
                columnNames = {"sourceUnifiedId", "account"}),
        indexes = {
                @Index(name = "idx_recon_timestamp", columnList = "txn_timestamp"),
                @Index(name = "idx_recon_account", columnList = "account")
        })
public class ReconTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Lineage + dedup: the unified_transaction id this row was derived from. */
    @Column(nullable = false)
    private Long sourceUnifiedId;

    @Column(name = "txn_timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private WalletAccount account;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 8)
    private String amountCurrency;

    /** Original statement source (MAYBANK_SA, GRAB, ALIPAY, ...), kept for traceability. */
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private SourceType source;

    @Column(nullable = false, length = 256)
    private String payee;

    @Column(length = 1024)
    private String description;

    /** EXPENSE, INCOME, TRANSFER — copied, except synthesized Grab top-ups which are INCOME. */
    @Column(length = 16)
    private String type;

    @Column(length = 64)
    private String category;

    @Column(length = 256)
    private String reference;

    @Column(precision = 14, scale = 2)
    private BigDecimal originalAmount;

    @Column(length = 8)
    private String originalCurrency;

    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSourceUnifiedId() { return sourceUnifiedId; }
    public void setSourceUnifiedId(Long v) { this.sourceUnifiedId = v; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime v) { this.timestamp = v; }
    public WalletAccount getAccount() { return account; }
    public void setAccount(WalletAccount v) { this.account = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public String getAmountCurrency() { return amountCurrency; }
    public void setAmountCurrency(String v) { this.amountCurrency = v; }
    public SourceType getSource() { return source; }
    public void setSource(SourceType v) { this.source = v; }
    public String getPayee() { return payee; }
    public void setPayee(String v) { this.payee = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }
    public String getReference() { return reference; }
    public void setReference(String v) { this.reference = v; }
    public BigDecimal getOriginalAmount() { return originalAmount; }
    public void setOriginalAmount(BigDecimal v) { this.originalAmount = v; }
    public String getOriginalCurrency() { return originalCurrency; }
    public void setOriginalCurrency(String v) { this.originalCurrency = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
