-- 扩展列后对已有退款意图回填；GREATEST保留并发新版本已经预留的较大值。
-- V5/V6已经在隔离库应用，追加迁移而不是修改既有迁移校验和。
UPDATE payment_attempt p
SET p.refund_reserved=GREATEST(p.refund_reserved,
 (SELECT COALESCE(SUM(r.amount),0) FROM payment_refund r WHERE r.tenant_id=p.tenant_id AND r.order_id=p.order_id));
