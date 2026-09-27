-- 在 commerce_phase7_bench 专用库执行；UPDATE 只做 EXPLAIN，不改变额度。
SET time_zone='+00:00';
EXPLAIN ANALYZE SELECT campaign_id,version,policy_json,rule_json FROM marketing_campaign
 WHERE tenant_id='phase7-large' AND store_id='store1' AND status='PUBLISHED'
 AND valid_from<='2026-09-27 23:00:00' AND valid_to>'2026-09-27 23:00:00'
 ORDER BY campaign_id LIMIT 101;
EXPLAIN ANALYZE SELECT EXISTS(SELECT 1 FROM marketing_audience_member WHERE tenant_id='phase7-large'
 AND audience_id='storage-probe' AND version=1 AND member_id='probe030000');
EXPLAIN ANALYZE SELECT * FROM marketing_execution WHERE tenant_id='phase7-large'
 AND order_id>'o00030000' ORDER BY order_id,campaign_id LIMIT 50;
EXPLAIN ANALYZE SELECT * FROM benefit_grant WHERE tenant_id='phase7-large' AND grant_id='g00030000';
EXPLAIN ANALYZE SELECT * FROM benefit_grant WHERE tenant_id='phase7-large'
 AND member_id='m000010' AND grant_id>'g00030000' ORDER BY grant_id LIMIT 50;
EXPLAIN ANALYZE SELECT * FROM platform_event WHERE tenant_id='phase7-large'
 AND event_id>'phase7-history-00030000' ORDER BY event_id LIMIT 50;
EXPLAIN ANALYZE SELECT * FROM platform_inbox WHERE consumer_id='marketing-execution-projection-v1'
 AND event_id='phase7-history-00030000';
EXPLAIN ANALYZE SELECT * FROM marketing_campaign WHERE tenant_id='phase7-large'
 AND campaign_id='c000000' ORDER BY version LIMIT 51;
EXPLAIN ANALYZE SELECT * FROM benefit_coupon_definition WHERE tenant_id='phase7-small'
 AND definition_id=(SELECT MAX(definition_id) FROM benefit_coupon_definition WHERE tenant_id='phase7-small') AND version=1;
EXPLAIN UPDATE benefit_coupon_definition SET reserved=reserved+1 WHERE tenant_id='phase7-small'
 AND definition_id='missing-plan-probe' AND version=1 AND issued+reserved<quota;
