# MySQL EXPLAIN ANALYZE

```text
-> Limit: 5 row(s)  (cost=8.27..8.27 rows=5) (actual time=0.0688..0.0688 rows=0 loops=1)
    -> Sort: c.due_at, c.instance_id, limit input to 5 row(s) per chunk  (cost=8.27..8.27 rows=5) (actual time=0.0687..0.0687 rows=0 loops=1)
        -> Table scan on c  (cost=4.56..6.61 rows=5) (actual time=0.0616..0.0616 rows=0 loops=1)
            -> Union materialize with deduplication  (cost=4.05..4.05 rows=5) (actual time=0.0606..0.0606 rows=0 loops=1)
                -> Limit: 5 row(s)  (cost=0.71 rows=1) (actual time=0.0204..0.0204 rows=0 loops=1)
                    -> Index range scan on journey_instance using idx_journey_due over (tenant_id = 'p8-48c61544-future' AND status = 'RUNNING' AND due_at <= '2026-09-28 00:17:48.177000'), with index condition: ((journey_instance.`status` = 'RUNNING') and (journey_instance.tenant_id = 'p8-48c61544-future') and (journey_instance.due_at <= <cache>(utc_timestamp(3))))  (cost=0.71 rows=1) (actual time=0.0154..0.0154 rows=0 loops=1)
                -> Limit: 5 row(s)  (cost=0.71 rows=1) (actual time=0.00296..0.00296 rows=0 loops=1)
                    -> Index range scan on journey_instance using idx_journey_due over (tenant_id = 'p8-48c61544-future' AND status = 'WAITING' AND due_at <= '2026-09-28 00:17:48.177000'), with index condition: ((journey_instance.`status` = 'WAITING') and (journey_instance.tenant_id = 'p8-48c61544-future') and (journey_instance.due_at <= <cache>(utc_timestamp(3))))  (cost=0.71 rows=1) (actual time=0.00292..0.00292 rows=0 loops=1)
                -> Limit: 5 row(s)  (cost=0.71 rows=1) (actual time=0.0132..0.0132 rows=0 loops=1)
                    -> Sort: journey_instance.due_at, journey_instance.instance_id, limit input to 5 row(s) per chunk  (cost=0.71 rows=1) (actual time=0.00592..0.00592 rows=0 loops=1)
                        -> Index range scan on journey_instance using idx_journey_deadline over (tenant_id = 'p8-48c61544-future' AND status = 'RUNNING' AND deadline <= '2026-09-28 00:17:48.177000'), with index condition: ((journey_instance.`status` = 'RUNNING') and (journey_instance.tenant_id = 'p8-48c61544-future') and (journey_instance.deadline <= <cache>(utc_timestamp(3))))  (cost=0.71 rows=1) (actual time=0.00288..0.00288 rows=0 loops=1)
                -> Limit: 5 row(s)  (cost=0.71 rows=1) (actual time=0.00354..0.00354 rows=0 loops=1)
                    -> Sort: journey_instance.due_at, journey_instance.instance_id, limit input to 5 row(s) per chunk  (cost=0.71 rows=1) (actual time=0.0035..0.0035 rows=0 loops=1)
                        -> Index range scan on journey_instance using idx_journey_deadline over (tenant_id = 'p8-48c61544-future' AND status = 'WAITING' AND deadline <= '2026-09-28 00:17:48.177000'), with index condition: ((journey_instance.`status` = 'WAITING') and (journey_instance.tenant_id = 'p8-48c61544-future') and (journey_instance.deadline <= <cache>(utc_timestamp(3))))  (cost=0.71 rows=1) (actual time=0.00275..0.00275 rows=0 loops=1)
                -> Limit: 5 row(s)  (cost=0.71 rows=1) (actual time=0.00262..0.00262 rows=0 loops=1)
                    -> Sort: journey_instance.due_at, journey_instance.instance_id, limit input to 5 row(s) per chunk  (cost=0.71 rows=1) (actual time=0.0025..0.0025 rows=0 loops=1)
                        -> Index range scan on journey_instance using idx_journey_deadline over (tenant_id = 'p8-48c61544-future' AND status = 'ISOLATED' AND deadline <= '2026-09-28 00:17:48.177000'), with index condition: ((journey_instance.`status` = 'ISOLATED') and (journey_instance.tenant_id = 'p8-48c61544-future') and (journey_instance.deadline <= <cache>(utc_timestamp(3))))  (cost=0.71 rows=1) (actual time=0.00221..0.00221 rows=0 loops=1)
```
