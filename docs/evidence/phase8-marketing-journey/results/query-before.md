# MySQL EXPLAIN ANALYZE

```text
-> Limit: 5 row(s)  (cost=420 rows=5) (actual time=28..28 rows=0 loops=1)
    -> Sort: journey_instance.due_at, journey_instance.instance_id, limit input to 5 row(s) per chunk  (cost=420 rows=25826) (actual time=27.9..27.9 rows=0 loops=1)
        -> Filter: (((journey_instance.`status` in ('RUNNING','WAITING')) and (journey_instance.due_at <= <cache>(utc_timestamp()))) or ((journey_instance.`status` in ('RUNNING','WAITING','ISOLATED')) and (journey_instance.deadline <= <cache>(utc_timestamp()))))  (cost=420 rows=25826) (actual time=27.9..27.9 rows=0 loops=1)
            -> Index lookup on journey_instance using PRIMARY (tenant_id='p8-query-eb649f85')  (cost=420 rows=25826) (actual time=0.0489..10.1 rows=50000 loops=1)


```
