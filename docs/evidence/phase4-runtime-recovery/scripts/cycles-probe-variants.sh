q(){ docker exec -i -e MYSQL_PWD="$COMMERCE_DB_PASSWORD" dev-infra-mysql84-1 mysql -u"$COMMERCE_DB_USER" commerce_test_20260923 "$@" 2>&1 | { grep -v 'Using a password' || true; }; }
t=p4bench-probe
q -e "DELETE FROM member_cycle_policy WHERE tenant_id='$t'; DELETE FROM member_record WHERE tenant_id='$t';"
q <<SQL
SET SESSION cte_max_recursion_depth=1000000;
INSERT INTO member_cycle_policy(tenant_id,version,effective_from,policy_json,rolled_out) VALUES('$t',1,TIMESTAMPADD(DAY,-10,CURRENT_TIMESTAMP(3)),JSON_OBJECT('version',1),TRUE);
INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,cycle_due_at)
 WITH RECURSIVE s(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM s WHERE i<100000) SELECT '$t',CONCAT('m',LPAD(i,7,'0')),CONCAT('a',i),'bench','L1',TIMESTAMPADD(DAY,29,CURRENT_TIMESTAMP(3)) FROM s;
ANALYZE TABLE member_record;
SQL
for probe in "(SELECT m.cycle_due_at FROM member_record m FORCE INDEX(ix_member_cycle_due) WHERE m.tenant_id=p.tenant_id ORDER BY m.tenant_id,m.cycle_due_at LIMIT 1)<=CURRENT_TIMESTAMP(3)" \
             "EXISTS(SELECT /*+ NO_SEMIJOIN() */ 1 FROM member_record m FORCE INDEX(ix_member_cycle_due) WHERE m.tenant_id=p.tenant_id AND m.cycle_due_at<=CURRENT_TIMESTAMP(3))" \
             "(SELECT MIN(m.cycle_due_at) FROM member_record m FORCE INDEX(ix_member_cycle_due) WHERE m.tenant_id=p.tenant_id)<=CURRENT_TIMESTAMP(3)"; do
 echo "=== $probe"; q -e "EXPLAIN ANALYZE SELECT DISTINCT p.tenant_id FROM member_cycle_policy p WHERE p.tenant_id>'p4bench-p' AND p.tenant_id<'p4bench-q' AND p.effective_from<=CURRENT_TIMESTAMP(3) AND $probe ORDER BY p.tenant_id LIMIT 50\G" | grep -E 'EXPLAIN|lookup on m|range scan on m|Sort: m|Aggregate|Index range' | cut -c1-230
done
q -e "DELETE FROM member_cycle_policy WHERE tenant_id='$t'; DELETE FROM member_record WHERE tenant_id='$t';"
