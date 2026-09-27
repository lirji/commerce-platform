# MySQL EXPLAIN output

Synthetic benchmark schema, UTC session; UPDATE is EXPLAIN only.

```text
EXPLAIN
-> Limit: 101 row(s)  (cost=96.3 rows=80) (actual time=0.216..0.231 rows=80 loops=1)
    -> Sort: marketing_campaign.campaign_id, limit input to 101 row(s) per chunk  (cost=96.3 rows=80) (actual time=0.21..0.222 rows=80 loops=1)
        -> Index range scan on marketing_campaign using ix_campaign_candidate_window over (tenant_id = 'phase7-large' AND store_id = 'store1' AND status = 'PUBLISHED' AND '2026-09-27 23:00:00.000' < valid_to), with index condition: ((marketing_campaign.`status` = 'PUBLISHED') and (marketing_campaign.store_id = 'store1') and (marketing_campaign.tenant_id = 'phase7-large') and (marketing_campaign.valid_from <= TIMESTAMP'2026-09-27 23:00:00') and (marketing_campaign.valid_to > TIMESTAMP'2026-09-27 23:00:00'))  (cost=96.3 rows=80) (actual time=0.0349..0.146 rows=80 loops=1)

EXPLAIN
-> Rows fetched before execution  (cost=0..0 rows=1) (actual time=84e-6..84e-6 rows=1 loops=1)
-> Select #2 (subquery in projection; run only once)
    -> Limit: 1 row(s)  (cost=0..0 rows=1) (actual time=249e-6..249e-6 rows=1 loops=1)
        -> Rows fetched before execution  (cost=0..0 rows=1) (actual time=83e-6..83e-6 rows=1 loops=1)

EXPLAIN
-> Limit: 50 row(s)  (cost=13945 rows=50) (actual time=0.0795..0.119 rows=50 loops=1)
    -> Filter: ((marketing_execution.tenant_id = 'phase7-large') and (marketing_execution.order_id > 'o00030000'))  (cost=13945 rows=30345) (actual time=0.0712..0.108 rows=50 loops=1)
        -> Index range scan on marketing_execution using PRIMARY over (tenant_id = 'phase7-large' AND 'o00030000' < order_id)  (cost=13945 rows=30345) (actual time=0.0699..0.103 rows=50 loops=1)

EXPLAIN
-> Rows fetched before execution  (cost=0..0 rows=1) (actual time=125e-6..166e-6 rows=1 loops=1)

EXPLAIN
-> Limit: 50 row(s)  (cost=18.3 rows=40) (actual time=0.143..2.08 rows=40 loops=1)
    -> Index range scan on benefit_grant using ix_entitlement_wallet over (tenant_id = 'phase7-large' AND member_id = 'm000010' AND 'g00030000' < grant_id), with index condition: ((benefit_grant.member_id = 'm000010') and (benefit_grant.tenant_id = 'phase7-large') and (benefit_grant.grant_id > 'g00030000'))  (cost=18.3 rows=40) (actual time=0.143..2.08 rows=40 loops=1)

EXPLAIN
-> Limit: 50 row(s)  (cost=1289 rows=50) (actual time=0.0192..0.0447 rows=50 loops=1)
    -> Filter: ((platform_event.tenant_id = 'phase7-large') and (platform_event.event_id > 'phase7-history-00030000'))  (cost=1289 rows=12713) (actual time=0.0188..0.042 rows=50 loops=1)
        -> Index range scan on platform_event using PRIMARY over ('phase7-history-00030000' < event_id)  (cost=1289 rows=25426) (actual time=0.0158..0.0349 rows=50 loops=1)

EXPLAIN
-> Rows fetched before execution  (cost=0..0 rows=1) (actual time=82e-6..124e-6 rows=1 loops=1)

EXPLAIN
-> Limit: 51 row(s)  (cost=0.99 rows=1) (actual time=0.0226..0.0235 rows=1 loops=1)
    -> Index lookup on marketing_campaign using PRIMARY (tenant_id='phase7-large', campaign_id='c000000')  (cost=0.99 rows=1) (actual time=0.011..0.0118 rows=1 loops=1)

EXPLAIN
-> Rows fetched before execution  (cost=0..0 rows=1) (actual time=0..41e-6 rows=1 loops=1)

id	select_type	table	partitions	type	possible_keys	key	key_len	ref	rows	filtered	Extra
1	UPDATE	benefit_coupon_definition	NULL	range	PRIMARY,ix_coupon_definition_store	PRIMARY	524	const,const,const	1	100.00	Using where

```
