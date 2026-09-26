SET SESSION cte_max_recursion_depth=100000;
DELETE FROM payment_attempt WHERE tenant_id LIKE 'qp-%';
INSERT INTO payment_attempt(tenant_id,payment_id,order_id,amount,currency,provider,status,version,check_attempts,next_check_at)
WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<60000) SELECT 'qp-big',CONCAT('p',i),CONCAT('h',i),10,'CNY','SANDBOX','PAID',1,1,NOW(3)-INTERVAL 30 DAY FROM n;
INSERT INTO payment_attempt(tenant_id,payment_id,order_id,amount,currency,provider,status,version,check_attempts,next_check_at)
WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<20000) SELECT CONCAT('qp-w',LPAD(i MOD 2000,4,'0')),CONCAT('pw',i),CONCAT('w',i),10,'CNY','SANDBOX','UNKNOWN',0,0,NOW(3)-INTERVAL 1 MINUTE FROM n;
INSERT INTO payment_attempt(tenant_id,payment_id,order_id,amount,currency,provider,status,version,check_attempts,next_check_at)
WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<5) SELECT 'qp-big',CONCAT('pu',i),CONCAT('e',i),10,'CNY','SANDBOX','UNKNOWN',0,0,NOW(3)-INTERVAL 1 MINUTE FROM n;
ANALYZE TABLE payment_attempt;
DELETE FROM member_point_lot WHERE tenant_id LIKE 'qp-%';
INSERT IGNORE INTO member_record(tenant_id,member_id,actor_id,display_name,member_level) SELECT DISTINCT tenant_id,'m1','buyer','x','L1' FROM order_record WHERE tenant_id LIKE 'qp-%';
INSERT INTO member_point_lot(tenant_id,lot_id,member_id,policy_version,credited,remaining,expired,expires_at)
WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<60000) SELECT 'qp-big',CONCAT('l',i),'m1',1,10,0,10,NOW(3)-INTERVAL 30 DAY FROM n;
INSERT INTO member_point_lot(tenant_id,lot_id,member_id,policy_version,credited,remaining,expires_at)
WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<20000) SELECT CONCAT('qp-w',LPAD(i MOD 2000,4,'0')),CONCAT('lw',i),'m1',1,10,10,NOW(3)-INTERVAL 1 MINUTE FROM n;
INSERT INTO member_point_lot(tenant_id,lot_id,member_id,policy_version,credited,remaining,expires_at)
WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<5) SELECT 'qp-big',CONCAT('lu',i),'m1',1,10,10,NOW(3)-INTERVAL 1 MINUTE FROM n;
ANALYZE TABLE member_point_lot;
