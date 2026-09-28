# MySQL EXPLAIN ANALYZE

```text
-> Limit: 50 row(s)  (actual time=0.0653..0.0654 rows=2 loops=1)
    -> Sort: journey_instance.tenant_id, limit input to 50 row(s) per chunk  (actual time=0.065..0.0651 rows=2 loops=1)
        -> Table scan on <temporary>  (cost=3.26..3.26 rows=0.667) (actual time=0.0579..0.0582 rows=2 loops=1)
            -> Temporary table with deduplication  (cost=0.758..0.758 rows=0.667) (actual time=0.057..0.057 rows=2 loops=1)
                -> Filter: ((journey_instance.tenant_id > '') and (journey_instance.`status` in ('RUNNING','WAITING')) and (journey_instance.due_at <= <cache>(utc_timestamp(3))))  (cost=0.691 rows=0.667) (actual time=0.0143..0.017 rows=2 loops=1)
                    -> Covering index range scan on journey_instance using idx_journey_global_due over (status = 'RUNNING' AND due_at <= '2026-09-28 00:17:48.220000') OR (status = 'WAITING' AND due_at <= '2026-09-28 00:17:48.220000')  (cost=0.691 rows=2) (actual time=0.0103..0.0127 rows=2 loops=1)
```
