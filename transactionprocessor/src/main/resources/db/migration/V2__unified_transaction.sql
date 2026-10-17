-- V2: single canonical transaction table for the stateless processing pipeline.
--
-- The pipeline persists ONLY this table. Legacy intermediate tables from V1
-- (statement_file, raw_transaction, normalized_transaction, transaction_match,
-- wallet_transaction, export_batch) are intentionally left untouched: frozen
-- downstream code (reconciliation/review/wallet/transactions UI) still reads them,
-- and they will be dropped by a later migration once downstream is redesigned.
-- No existing data is migrated: those rows describe superseded file-level import
-- state, and transaction-level deduplication rebuilds truth from reprocessing.
--
-- Dedup invariant (second line of defense; primary dedup is the batch pre-query):
-- exact match on canonical (timestamp, payee, amount). Source is deliberately
-- excluded. NUMERIC equality makes 2, 2.0 and 2.00 identical at the DB level.
CREATE TABLE IF NOT EXISTS unified_transaction (
    id BIGSERIAL PRIMARY KEY,
    txn_timestamp TIMESTAMP NOT NULL,
    payee VARCHAR(256) NOT NULL,
    amount NUMERIC(14,2) NOT NULL,
    amount_currency VARCHAR(8) NOT NULL,
    source VARCHAR(32) NOT NULL,
    reference VARCHAR(256),
    type VARCHAR(16),
    account VARCHAR(16),
    category VARCHAR(64),
    description VARCHAR(1024),
    original_amount NUMERIC(14,2),
    original_currency VARCHAR(8),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT uq_unified_txn_key UNIQUE (txn_timestamp, payee, amount)
);
CREATE INDEX IF NOT EXISTS idx_unified_txn_timestamp ON unified_transaction(txn_timestamp);
CREATE INDEX IF NOT EXISTS idx_unified_txn_source ON unified_transaction(source);
