ALTER TABLE payment_attempt
 ADD COLUMN channel_transaction_id VARCHAR(64) NULL COMMENT '已核验渠道收款流水号用于防止重复认领同笔收款',
 ADD COLUMN evidence_json LONGTEXT NULL COMMENT '服务端核验的渠道证据快照不含密钥',
 ADD UNIQUE KEY uk_payment_channel_transaction(tenant_id,provider,channel_transaction_id),
 ADD CONSTRAINT ck_payment_evidence CHECK(evidence_json IS NULL OR JSON_VALID(evidence_json));
