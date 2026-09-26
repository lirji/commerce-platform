-- 查询计划数据集：qp-big 有 60000 个历史已完成订单与 30 个到期未付订单；200 个小租户各 1 个到期未付订单。
SET SESSION cte_max_recursion_depth=100000;
DELETE FROM order_record WHERE tenant_id LIKE 'qp-%';
INSERT INTO order_record(tenant_id,order_id,member_id,store_id,merchant_id,quote_id,payable,status,payment_kind,version,created_at,expires_at,items_json,address_cipher,address_key_version)
WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<60000)
SELECT 'qp-big',CONCAT('h',i),'m1','s1','mc1',CONCAT('qh',i),10,'COMPLETED','CHANNEL_REQUIRED',3,NOW(3)-INTERVAL 30 DAY,NOW(3)-INTERVAL 30 DAY+INTERVAL 15 MINUTE,'[]',X'00',1 FROM n;
INSERT INTO order_record(tenant_id,order_id,member_id,store_id,merchant_id,quote_id,payable,status,payment_kind,version,created_at,expires_at,items_json,address_cipher,address_key_version)
WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<30)
SELECT 'qp-big',CONCAT('e',i),'m1','s1','mc1',CONCAT('qe',i),10,'PENDING_PAYMENT','CHANNEL_REQUIRED',0,NOW(3)-INTERVAL 1 HOUR,NOW(3)-INTERVAL 30 MINUTE,'[]',X'00',1 FROM n;
INSERT INTO order_record(tenant_id,order_id,member_id,store_id,merchant_id,quote_id,payable,status,payment_kind,version,created_at,expires_at,items_json,address_cipher,address_key_version)
WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<200)
SELECT CONCAT('qp-s',LPAD(i,3,'0')),'e1','m1','s1','mc1','qs1',10,'PENDING_PAYMENT','CHANNEL_REQUIRED',0,NOW(3)-INTERVAL 1 HOUR,NOW(3)-INTERVAL 30 MINUTE,'[]',X'00',1 FROM n;
ANALYZE TABLE order_record;
