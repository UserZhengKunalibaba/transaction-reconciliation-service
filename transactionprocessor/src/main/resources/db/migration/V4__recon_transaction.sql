-- V4: single output table for the rebuilt reconciliation module.
--
-- Input is unified_transaction. Each run copies rows into one of the three
-- WalletApp accounts (Maybank = MAYBANK_SA + MAYBANK_CC union, Grab = GRAB +
-- synthesized top-up incomes, Cash = everything else). No intermediate state,
-- no status columns, no state machine.
--
-- Idempotency: UNIQUE (source_unified_id, account). A Maybank→GrabPay top-up
-- legitimately yields two rows sharing one source id (Maybank copy + Grab
-- income, different accounts); every other input row yields exactly one row.
-- Retries therefore re-generate identical keys and insert nothing new.
CREATE TABLE IF NOT EXISTS recon_transaction (
    id BIGSERIAL PRIMARY KEY,
    source_unified_id BIGINT NOT NULL,
    txn_timestamp TIMESTAMP NOT NULL,
    account VARCHAR(16) NOT NULL,
    amount NUMERIC(14,2) NOT NULL,
    amount_currency VARCHAR(8) NOT NULL,
    source VARCHAR(32) NOT NULL,
    payee VARCHAR(256) NOT NULL,
    description VARCHAR(1024),
    type VARCHAR(16),
    category VARCHAR(64),
    reference VARCHAR(256),
    original_amount NUMERIC(14,2),
    original_currency VARCHAR(8),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    CONSTRAINT uq_recon_source_account UNIQUE (source_unified_id, account)
);
CREATE INDEX IF NOT EXISTS idx_recon_timestamp ON recon_transaction(txn_timestamp);
CREATE INDEX IF NOT EXISTS idx_recon_account ON recon_transaction(account);
