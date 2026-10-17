-- V3: rename source WECHAT → WECHATPAY for bucket/enum consistency.
--
-- Each upload bucket now declares a different source enum
-- (MAYBANK_SA, MAYBANK_CC, GRAB, TNG, ALIPAY, WECHATPAY). Existing rows keep
-- their identities: the deduplication key (txn_timestamp, payee, amount) is
-- untouched, so this rename changes labels only — reprocessing any file after
-- this migration still deduplicates exactly as before.
UPDATE unified_transaction SET source = 'WECHATPAY' WHERE source = 'WECHAT';
-- Legacy frozen tables (kept mapped for the later downstream redesign).
UPDATE normalized_transaction SET source = 'WECHATPAY' WHERE source = 'WECHAT';
UPDATE raw_transaction SET source = 'WECHATPAY' WHERE source = 'WECHAT';
UPDATE statement_file SET source = 'WECHATPAY' WHERE source = 'WECHAT';
