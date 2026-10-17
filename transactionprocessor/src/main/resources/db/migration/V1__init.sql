-- V1: initial schema for statement ETL / reconciliation
CREATE TABLE IF NOT EXISTS statement_file (
    id BIGSERIAL PRIMARY KEY,
    filename VARCHAR(1024) NOT NULL,
    source VARCHAR(32) NOT NULL,
    file_hash VARCHAR(64) NOT NULL UNIQUE,
    file_size BIGINT,
    file_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    error_message VARCHAR(2048),
    parser_version VARCHAR(32) NOT NULL,
    transaction_count INTEGER DEFAULT 0,
    imported_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_statement_file_source ON statement_file(source);

CREATE TABLE IF NOT EXISTS raw_transaction (
    id BIGSERIAL PRIMARY KEY,
    statement_file_id BIGINT NOT NULL REFERENCES statement_file(id) ON DELETE CASCADE,
    source VARCHAR(32) NOT NULL,
    source_transaction_id VARCHAR(256),
    raw_date VARCHAR(64),
    raw_description VARCHAR(1024),
    raw_amount VARCHAR(64),
    raw_currency VARCHAR(8),
    raw_payload TEXT,
    raw_data_hash VARCHAR(64),
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_raw_txn_source_txn ON raw_transaction(source, source_transaction_id);
CREATE INDEX IF NOT EXISTS idx_raw_txn_file ON raw_transaction(statement_file_id);
CREATE INDEX IF NOT EXISTS idx_raw_txn_hash ON raw_transaction(raw_data_hash);

CREATE TABLE IF NOT EXISTS normalized_transaction (
    id BIGSERIAL PRIMARY KEY,
    raw_transaction_id BIGINT NOT NULL REFERENCES raw_transaction(id) ON DELETE CASCADE,
    source VARCHAR(32) NOT NULL,
    transaction_date DATE,
    transaction_time TIME,
    account VARCHAR(16),
    type VARCHAR(16),
    amount_myr NUMERIC(14,2),
    original_amount NUMERIC(14,2),
    original_currency VARCHAR(8),
    description VARCHAR(1024),
    merchant VARCHAR(256),
    category VARCHAR(64),
    reference VARCHAR(256),
    status VARCHAR(16) DEFAULT 'PENDING',
    grouped BOOLEAN DEFAULT FALSE,
    group_id BIGINT
);
CREATE INDEX IF NOT EXISTS idx_norm_txn_date ON normalized_transaction(transaction_date);
CREATE INDEX IF NOT EXISTS idx_norm_txn_source ON normalized_transaction(source);
CREATE INDEX IF NOT EXISTS idx_norm_txn_status ON normalized_transaction(status);
CREATE INDEX IF NOT EXISTS idx_norm_txn_account ON normalized_transaction(account);

CREATE TABLE IF NOT EXISTS transaction_match (
    id BIGSERIAL PRIMARY KEY,
    normalized_transaction_id BIGINT NOT NULL REFERENCES normalized_transaction(id) ON DELETE CASCADE,
    maybank_transaction_id BIGINT REFERENCES normalized_transaction(id) ON DELETE CASCADE,
    match_score INTEGER,
    match_reason VARCHAR(2048),
    match_status VARCHAR(16) DEFAULT 'PENDING',
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_match_norm ON transaction_match(normalized_transaction_id);
CREATE INDEX IF NOT EXISTS idx_match_maybank ON transaction_match(maybank_transaction_id);
CREATE INDEX IF NOT EXISTS idx_match_status ON transaction_match(match_status);

CREATE TABLE IF NOT EXISTS export_batch (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    format VARCHAR(8),
    transaction_count INTEGER DEFAULT 0,
    status VARCHAR(16) DEFAULT 'GENERATED',
    filename VARCHAR(1024)
);

CREATE TABLE IF NOT EXISTS wallet_transaction (
    id BIGSERIAL PRIMARY KEY,
    normalized_transaction_id BIGINT NOT NULL REFERENCES normalized_transaction(id) ON DELETE CASCADE,
    account VARCHAR(16),
    date DATE,
    amount NUMERIC(14,2),
    description VARCHAR(1024),
    category VARCHAR(64),
    status VARCHAR(16) DEFAULT 'READY',
    export_batch_id BIGINT REFERENCES export_batch(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_wallet_norm ON wallet_transaction(normalized_transaction_id);
CREATE INDEX IF NOT EXISTS idx_wallet_batch ON wallet_transaction(export_batch_id);
CREATE INDEX IF NOT EXISTS idx_wallet_account ON wallet_transaction(account);
