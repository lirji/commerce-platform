-- 仅扩展：租户到期索引让轮转发现与租户内FIFO只扫描待投递行，不随已投递历史增长；失败证据只记录消费者与异常类型，不含载荷或异常文本。
ALTER TABLE platform_event
 ADD COLUMN last_error VARCHAR(160) NULL COMMENT '最近一次失败的消费者与异常类型，不含载荷',
 ADD KEY ix_event_tenant_due(status,tenant_id,available_at);
