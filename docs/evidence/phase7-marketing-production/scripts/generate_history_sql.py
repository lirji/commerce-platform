#!/usr/bin/env python3
"""Additional synthetic history and beyond-product-bound audience storage probe.

Print-only seeder for commerce_phase7_bench, no production writes/deletes.
The 50k membership probe bypasses the public 500-member contract deliberately;
it does not authorize increasing that contract.
"""
from generate_scale_sql import insert_many

print("SET time_zone='+00:00';")
insert_many("benefit_grant", ("tenant_id", "grant_id", "order_id", "member_id", "benefit_id",
    "benefit_version", "name", "status", "units", "remaining_units", "debt_units", "expires_at",
    "version", "source_type", "source_id"), [
    f"('phase7-large','g{n:08d}','hg{n:08d}','m{n%500:06d}','history',1,'历史权益','AVAILABLE',3,3,0,'2027-01-01',0,'ORDER','hg{n:08d}')"
    for n in range(50000)])
insert_many("platform_event", ("event_id", "tenant_id", "event_type", "aggregate_id", "aggregate_version",
    "payload_json", "status", "available_at"), [
    f"('phase7-history-{n:08d}','phase7-large','benefit.grant.available.v1','g{n:08d}',1,'{{}}','DELIVERED','2026-09-01')"
    for n in range(50000)])
insert_many("platform_inbox", ("consumer_id", "event_id", "tenant_id"), [
    f"('marketing-execution-projection-v1','phase7-history-{n:08d}','phase7-large')" for n in range(50000)])
print("INSERT INTO marketing_audience_snapshot(tenant_id,audience_id,version,name,source,watermark,valid_until,member_count) "
      "VALUES('phase7-large','storage-probe',1,'存储探针','phase7-storage','2026-09-01','2027-01-01',50000);")
insert_many("marketing_audience_member", ("tenant_id", "audience_id", "version", "member_id"), [
    f"('phase7-large','storage-probe',1,'probe{n:06d}')" for n in range(50000)])
