-- 仅扩展索引：保留期清理按主键批量删除事件的Inbox行、按创建时间有界删除命令，不做全表扫描（证据见phase4 retention-execution）。
-- Inbox主键是(consumer_id,event_id)，按事件删除需要事件维度索引；命令表主键不含时间。
ALTER TABLE platform_inbox ADD KEY ix_inbox_event(event_id);
ALTER TABLE platform_command ADD KEY ix_command_created(created_at);
