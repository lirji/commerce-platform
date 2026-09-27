-- 有效期过滤先走租户/店铺/状态与结束时间，避免大量过期发布版本撑爆候选上限。
CREATE INDEX ix_campaign_candidate_window
 ON marketing_campaign(tenant_id,store_id,status,valid_to,valid_from,campaign_id);
