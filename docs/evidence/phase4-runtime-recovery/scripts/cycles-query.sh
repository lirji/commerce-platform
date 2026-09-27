#!/usr/bin/env bash
# L4证据：在测试库隔离的p4bench-租户中构造N名已考核且未到期的会员（外加1名未考核会员），
# 对周期考核车道的发现与租户内取数执行EXPLAIN ANALYZE。用法: cycles-query.sh <N> <before|after>；结束后清理夹具。
set -Eeuo pipefail
root="$(cd "$(dirname "$0")/../../../.." && pwd)"; source "$root/.local/runtime.env"
q(){ docker exec -i -e MYSQL_PWD="$COMMERCE_DB_PASSWORD" dev-infra-mysql84-1 mysql -u"$COMMERCE_DB_USER" commerce_test_20260923 "$@" 2>&1 | { grep -v 'Using a password' || true; }; }
n="$1"; shape="$2"; t="p4bench-cycles-$n"
cleanup(){ q -e "DELETE FROM member_work_retry WHERE tenant_id='$t'" >/dev/null 2>&1 || true
  q -e "DELETE FROM member_cycle_account WHERE tenant_id='$t'; DELETE FROM member_cycle_policy WHERE tenant_id='$t'; DELETE FROM member_record WHERE tenant_id='$t';"; }
cleanup
q <<SQL
SET SESSION cte_max_recursion_depth=1000000;
INSERT INTO member_cycle_policy(tenant_id,version,effective_from,policy_json) VALUES('$t',1,TIMESTAMPADD(DAY,-10,CURRENT_TIMESTAMP(3)),JSON_OBJECT('version',1));
INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level)
 WITH RECURSIVE s(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM s WHERE i<$n) SELECT '$t',CONCAT('m',LPAD(i,7,'0')),CONCAT('a',i),'bench','L1' FROM s;
INSERT INTO member_cycle_account(tenant_id,member_id,policy_version,cycle_start,cycle_end,current_growth,retention_growth,member_level,version)
 SELECT tenant_id,member_id,1,TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP(3)),TIMESTAMPADD(DAY,29,CURRENT_TIMESTAMP(3)),0,0,'L1',1 FROM member_record WHERE tenant_id='$t';
INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level) VALUES('$t','zz-new','zz-new','bench','L1');
ANALYZE TABLE member_record,member_cycle_account,member_cycle_policy;
SQL
if [[ "$shape" == before ]]; then
due_where="FROM member_record m JOIN member_cycle_policy p ON p.tenant_id=m.tenant_id AND p.effective_from<=CURRENT_TIMESTAMP(3)
 LEFT JOIN member_cycle_account a ON a.tenant_id=m.tenant_id AND a.member_id=m.member_id
 WHERE m.status<>'CLOSED' AND NOT EXISTS(SELECT 1 FROM member_cycle_policy newer WHERE newer.tenant_id=p.tenant_id AND newer.effective_from<=CURRENT_TIMESTAMP(3) AND (newer.effective_from>p.effective_from OR (newer.effective_from=p.effective_from AND newer.version>p.version)))
 AND (a.member_id IS NULL OR a.cycle_end<=CURRENT_TIMESTAMP(3) OR a.policy_version<>p.version)"
echo "== discovery (dueTenants)"; q -e "EXPLAIN ANALYZE SELECT DISTINCT m.tenant_id $due_where AND m.tenant_id>'' ORDER BY m.tenant_id LIMIT 50\G"
echo "== tenant visit (due)"; q -e "EXPLAIN ANALYZE SELECT m.tenant_id,m.member_id $due_where AND m.tenant_id='$t' ORDER BY COALESCE(a.cycle_end,p.effective_from),m.member_id LIMIT 10\G"
else
# 新形态（与CycleMapper.xml一致）：已考核会员的到期时间是周期结束（未来），新会员取纪元默认值。
q -e "UPDATE member_record SET cycle_due_at=TIMESTAMPADD(DAY,29,CURRENT_TIMESTAMP(3)) WHERE tenant_id='$t' AND member_id<>'zz-new'; UPDATE member_cycle_policy SET rolled_out=TRUE WHERE tenant_id='$t'; ANALYZE TABLE member_record;" >/dev/null
nb="(r.item_id IS NULL OR (r.quarantined_at IS NULL AND r.retry_at<=CURRENT_TIMESTAMP(3)))"
echo "== discovery (dueTenants)"; q -e "EXPLAIN ANALYZE SELECT tenant_id FROM (
 (SELECT DISTINCT p.tenant_id FROM member_cycle_policy p WHERE p.tenant_id>'' AND p.effective_from<=CURRENT_TIMESTAMP(3)
   AND (SELECT m.cycle_due_at FROM member_record m FORCE INDEX(ix_member_cycle_due) WHERE m.tenant_id=p.tenant_id ORDER BY m.tenant_id,m.cycle_due_at LIMIT 1)<=CURRENT_TIMESTAMP(3) ORDER BY p.tenant_id LIMIT 50)
 UNION (SELECT tenant_id FROM member_cycle_policy WHERE rolled_out=FALSE AND tenant_id>'' ORDER BY tenant_id LIMIT 50)) t ORDER BY tenant_id LIMIT 50\G"
echo "== tenant visit (due)"; q -e "EXPLAIN ANALYZE SELECT m.tenant_id,m.member_id,r.attempts,r.transient_attempts FROM member_record m FORCE INDEX(ix_member_cycle_due)
 LEFT JOIN member_work_retry r ON r.tenant_id=m.tenant_id AND r.lane='cycles' AND r.item_id=m.member_id
 WHERE m.tenant_id='$t' AND m.cycle_due_at<=CURRENT_TIMESTAMP(3) AND $nb ORDER BY m.cycle_due_at,m.member_id LIMIT 10\G"
fi
cleanup
