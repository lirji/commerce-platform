-- 精确门店队列沿租户、门店和订单游标取批，避免扫同租户其他门店的历史订单。
ALTER TABLE order_record ADD KEY ix_order_store_list (tenant_id, store_id, order_id);
