-- 仅扩展：租户轮转车道的发现与租户内取数只扫描未完成的工作行，不随租户历史增长（证据见phase3 database-query-evidence）。
-- 状态列在前：发现按单一状态沿租户顺序扫描、取满即停；租户内按(状态,租户,到期)精确区间取数。
ALTER TABLE order_record ADD KEY ix_order_tenant_expiry(status,tenant_id,expires_at);
ALTER TABLE payment_attempt ADD KEY ix_payment_tenant_check(status,tenant_id,next_check_at);
ALTER TABLE payment_refund ADD KEY ix_refund_tenant_check(status,tenant_id,next_check_at);
ALTER TABLE member_point_lot ADD KEY ix_point_tenant_expiry(active_balance,tenant_id,expires_at);
