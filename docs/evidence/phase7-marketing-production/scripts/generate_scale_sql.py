#!/usr/bin/env python3
"""Print deterministic Phase 7 benchmark rows for an isolated MySQL schema.

Run only against a dedicated benchmark database. This tool never deletes rows.
The tiers are local assumptions, not production forecasts.
"""

import json
import sys


RULE = json.dumps({"kind": "COMPARE", "field": "memberLevel", "operator": "EQ",
                   "valueType": "TEXT", "value": "VIP", "children": None}, separators=(",", ":"))
TIERS = (("small", 10, 10, 100, 1000),
         ("medium", 500, 50, 500, 10000),
         ("large", 4000, 80, 500, 50000))


def insert_many(table, columns, values, batch=500):
    for start in range(0, len(values), batch):
        chunk = values[start:start + batch]
        print(f"INSERT INTO {table}({','.join(columns)}) VALUES " + ",\n".join(chunk) + ";")


def main():
    print("SET time_zone='+00:00';")
    for tier, campaigns, live, members, history in TIERS:
        tenant = f"phase7-{tier}"
        campaign_rows = []
        for n in range(campaigns):
            # 既有发布活动中大多数已过期，检验有效时间过滤是否能避免候选溢出。
            current = n < live
            start = "2026-01-01 00:00:00" if current else "2025-01-01 00:00:00"
            end = "2027-01-01 00:00:00" if current else "2025-02-01 00:00:00"
            campaign_rows.append(
                f"('{tenant}','c{n:06d}',1,'store1','merchant1','基准活动','{start}','{end}',"
                f"20.00,1.00,'{RULE}','PUBLISHED',0)"
            )
        insert_many("marketing_campaign", ("tenant_id", "campaign_id", "version", "store_id",
                    "merchant_id", "name", "valid_from", "valid_to", "minimum_spend",
                    "discount_amount", "rule_json", "status", "lock_version"), campaign_rows)
        print("INSERT INTO marketing_audience_snapshot(tenant_id,audience_id,version,name,source,"
              "watermark,valid_until,member_count) VALUES "
              f"('{tenant}','sample',1,'基准人群','phase7-seed','2026-09-26 00:00:00',"
              f"'2026-09-28 00:00:00',{members});")
        insert_many("marketing_audience_member", ("tenant_id", "audience_id", "version", "member_id"),
                    [f"('{tenant}','sample',1,'m{n:06d}')" for n in range(members)])
        execution_rows = []
        for n in range(history):
            execution_rows.append(
                f"('{tenant}','o{n:08d}','c000000',1,'q{n:08d}','m{n % members:06d}',"
                "'store1',1.00,'ELIGIBLE','APPLIED','2026-09-27 00:00:00')"
            )
        insert_many("marketing_execution", ("tenant_id", "order_id", "campaign_id", "campaign_version",
                    "quote_id", "member_id", "store_id", "discount_amount", "reason_code", "status",
                    "evaluated_at"), execution_rows)
        print(f"-- {tenant}: campaigns={campaigns}, live={live}, audience={members}, executions={history}",
              file=sys.stderr)


if __name__ == "__main__":
    main()
