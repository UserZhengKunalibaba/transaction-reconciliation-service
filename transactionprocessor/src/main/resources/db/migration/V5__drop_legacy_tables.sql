-- V5: drop legacy V1 intermediate tables. Only unified_transaction (import)
-- and recon_transaction (recon output) remain.
DROP TABLE IF EXISTS wallet_transaction;
DROP TABLE IF EXISTS transaction_match;
DROP TABLE IF EXISTS normalized_transaction;
DROP TABLE IF EXISTS raw_transaction;
DROP TABLE IF EXISTS statement_file;
DROP TABLE IF EXISTS export_batch;
