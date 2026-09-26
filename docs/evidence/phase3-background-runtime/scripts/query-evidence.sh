#!/usr/bin/env bash
# 第三阶段查询计划证据：偏斜数据集（qp-big 60000条历史 + 2000个租户共20000条到期工作），
# 同一查询分别在忽略V37索引（=迁移前）与实际映射SQL（=迁移后）下EXPLAIN ANALYZE；结束后删除数据集。
set -Eeuo pipefail
ROOT=/Users/liruijun/personal/LLM/commerce-platform; S=$(dirname "$0"); OUT=$1
source $ROOT/.local/runtime.env
q(){ docker exec -i -e MYSQL_PWD="$COMMERCE_DB_PASSWORD" dev-infra-mysql84-1 mysql -u"$COMMERCE_DB_USER" commerce_test_20260923 "$@"; }
q < $S/seed-plans.sql >/dev/null
q < $S/seed-plans2.sql >/dev/null
q -e "SET SESSION cte_max_recursion_depth=100000; INSERT INTO order_record(tenant_id,order_id,member_id,store_id,merchant_id,quote_id,payable,status,payment_kind,version,created_at,expires_at,items_json,address_cipher,address_key_version) WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<20000) SELECT CONCAT('qp-w',LPAD(i MOD 2000,4,'0')),CONCAT('w',i),'m1','s1','mc1',CONCAT('qw',i),10,'PENDING_PAYMENT','CHANNEL_REQUIRED',0,NOW(3)-INTERVAL 1 HOUR,NOW(3)-INTERVAL 40 MINUTE,'[]',X'00',1 FROM n; ANALYZE TABLE order_record, payment_attempt, member_point_lot;" >/dev/null
OD="expires_at<=NOW(3) AND expiry_attempts<5 AND expiry_transient_attempts<300 AND (expiry_retry_at IS NULL OR expiry_retry_at<=NOW(3))"
PD="check_attempts<5 AND check_transient_failures<300 AND next_check_at<=CURRENT_TIMESTAMP(3)"
LD="remaining>0 AND expires_at<=NOW(3)"
run(){ local title=$1 sql=$2; echo "### $title"; q -e "EXPLAIN ANALYZE $sql\G" | sed -n '2,$p' | grep -E "actual time" | head -6 | cut -c1-260; echo; }
{
echo "# Phase 3 query plans (MySQL 8.4, local Docker). Dataset: qp-big = 60,000 completed orders/paid payments/zeroed lots + a few due; 2,000 tenants x 10 due orders/payments/lots; 200 small tenants x 1 due order."
echo "# BEFORE = same query with the V37 index ignored (the pre-V37 plan). AFTER = the SQL shipped in the mappers."
echo
run "orders expiryDue(qp-big) BEFORE" "SELECT order_id FROM order_record IGNORE INDEX(ix_order_tenant_expiry) WHERE tenant_id='qp-big' AND status IN ('PENDING_PAYMENT','PAYMENT_IN_PROGRESS') AND $OD ORDER BY expires_at,order_id LIMIT 10"
run "orders expiryDue(qp-big) AFTER" "SELECT order_id FROM order_record WHERE tenant_id='qp-big' AND status IN ('PENDING_PAYMENT','PAYMENT_IN_PROGRESS') AND $OD ORDER BY expires_at,order_id LIMIT 10"
run "orders expiryTenants from start BEFORE (single IN query, no hint)" "SELECT DISTINCT tenant_id FROM order_record IGNORE INDEX(ix_order_tenant_expiry) WHERE status IN ('PENDING_PAYMENT','PAYMENT_IN_PROGRESS') AND $OD AND tenant_id>'' ORDER BY tenant_id LIMIT 50"
run "orders expiryTenants from start AFTER (union per status, FORCE INDEX)" "SELECT tenant_id FROM ((SELECT DISTINCT tenant_id FROM order_record FORCE INDEX(ix_order_tenant_expiry) WHERE status='PENDING_PAYMENT' AND tenant_id>'' AND $OD ORDER BY tenant_id LIMIT 50) UNION (SELECT DISTINCT tenant_id FROM order_record FORCE INDEX(ix_order_tenant_expiry) WHERE status='PAYMENT_IN_PROGRESS' AND tenant_id>'' AND $OD ORDER BY tenant_id LIMIT 50)) t ORDER BY tenant_id LIMIT 50"
run "orders expiredLock (PK) AFTER" "SELECT order_id FROM order_record WHERE tenant_id='qp-big' AND order_id='e5' AND status IN ('PENDING_PAYMENT','PAYMENT_IN_PROGRESS') AND $OD FOR UPDATE SKIP LOCKED"
run "orders backlog (global) AFTER" "SELECT COALESCE(SUM(expiry_attempts<5),0) FROM order_record WHERE status IN ('PENDING_PAYMENT','PAYMENT_IN_PROGRESS') AND expires_at<=NOW(3)"
run "payments due(qp-big) BEFORE" "SELECT payment_id FROM payment_attempt IGNORE INDEX(ix_payment_tenant_check) WHERE tenant_id='qp-big' AND status IN ('UNKNOWN','OPEN') AND $PD ORDER BY next_check_at,payment_id LIMIT 5"
run "payments due(qp-big) AFTER" "SELECT payment_id FROM payment_attempt WHERE tenant_id='qp-big' AND status IN ('UNKNOWN','OPEN') AND $PD ORDER BY next_check_at,payment_id LIMIT 5"
run "payments dueTenants BEFORE" "SELECT DISTINCT tenant_id FROM payment_attempt IGNORE INDEX(ix_payment_tenant_check) WHERE tenant_id>'' AND status IN ('UNKNOWN','OPEN') AND $PD ORDER BY tenant_id LIMIT 50"
run "payments dueTenants AFTER" "SELECT tenant_id FROM ((SELECT DISTINCT tenant_id FROM payment_attempt FORCE INDEX(ix_payment_tenant_check) WHERE status='UNKNOWN' AND tenant_id>'' AND $PD ORDER BY tenant_id LIMIT 50) UNION (SELECT DISTINCT tenant_id FROM payment_attempt FORCE INDEX(ix_payment_tenant_check) WHERE status='OPEN' AND tenant_id>'' AND $PD ORDER BY tenant_id LIMIT 50)) t ORDER BY tenant_id LIMIT 50"
run "points due(qp-big) BEFORE" "SELECT lot_id FROM member_point_lot IGNORE INDEX(ix_point_tenant_expiry) WHERE tenant_id='qp-big' AND active_balance=TRUE AND $LD ORDER BY expires_at,lot_id LIMIT 20"
run "points due(qp-big) AFTER" "SELECT lot_id FROM member_point_lot WHERE tenant_id='qp-big' AND active_balance=TRUE AND $LD ORDER BY expires_at,lot_id LIMIT 20"
run "points dueTenants BEFORE" "SELECT DISTINCT tenant_id FROM member_point_lot IGNORE INDEX(ix_point_tenant_expiry) WHERE active_balance=TRUE AND $LD AND tenant_id>'' ORDER BY tenant_id LIMIT 50"
run "points dueTenants AFTER" "SELECT DISTINCT tenant_id FROM member_point_lot FORCE INDEX(ix_point_tenant_expiry) WHERE active_balance=TRUE AND $LD AND tenant_id>'' ORDER BY tenant_id LIMIT 50"
run "refunds tenants AFTER (no skew data; plan shape only)" "SELECT DISTINCT tenant_id FROM payment_refund FORCE INDEX(ix_refund_tenant_check) WHERE tenant_id>'' AND status='UNKNOWN' AND check_attempts<5 AND check_transient_failures<300 AND next_check_at<=CURRENT_TIMESTAMP(3) ORDER BY tenant_id LIMIT 50"
run "events freshTenants AFTER (attempts=0 filter)" "SELECT tenant_id FROM platform_event WHERE status='PENDING' AND available_at>TIMESTAMPADD(SECOND,-10,CURRENT_TIMESTAMP(3)) AND available_at<=CURRENT_TIMESTAMP(3) AND attempts=0 AND transient_attempts=0 AND event_type IN ('order.paid.v1','payment.paid.v1') GROUP BY tenant_id ORDER BY MIN(available_at),tenant_id LIMIT 50"
run "cycles dueTenants (unchanged shape, measured only)" "SELECT DISTINCT m.tenant_id FROM member_record m JOIN member_cycle_policy p ON p.tenant_id=m.tenant_id AND p.effective_from<=NOW(3) LEFT JOIN member_cycle_account a ON a.tenant_id=m.tenant_id AND a.member_id=m.member_id WHERE m.status<>'CLOSED' AND NOT EXISTS(SELECT 1 FROM member_cycle_policy newer WHERE newer.tenant_id=p.tenant_id AND newer.effective_from<=NOW(3) AND (newer.effective_from>p.effective_from OR (newer.effective_from=p.effective_from AND newer.version>p.version))) AND (a.member_id IS NULL OR a.cycle_end<=NOW(3) OR a.policy_version<>p.version) AND m.tenant_id>'' ORDER BY m.tenant_id LIMIT 50"
} > "$OUT"
q -e "DELETE FROM payment_attempt WHERE tenant_id LIKE 'qp-%'; DELETE FROM member_point_lot WHERE tenant_id LIKE 'qp-%'; DELETE FROM order_record WHERE tenant_id LIKE 'qp-%'; SELECT COUNT(*) remaining FROM order_record WHERE tenant_id LIKE 'qp-%';"
