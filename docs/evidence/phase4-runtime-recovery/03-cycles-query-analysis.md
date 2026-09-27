# P4.2 — Cycles Discovery Scalability (L4)

## 1. Problem, proven before changing anything (`cycles-query-before.txt`)

Old query (V23/Phase 3 `CycleMapper.xml`):
- due members = members of tenants with an effective policy, `LEFT JOIN` the cycle account, filtered to "no account, or cycle ended, or policy version changed";
- discovery = `DISTINCT tenant_id` over the same join.

Schema and indexes involved:
- `member_record` PK `(tenant_id, member_id)`;
- `member_cycle_account` PK `(tenant_id, member_id)` plus `idx_cycle_due(cycle_end, tenant_id, member_id)`;
- `member_cycle_policy` PK `(tenant_id, version)`.

The "no account" branch is an anti-join that cannot be answered from an index.

Dataset (`scripts/cycles-query.sh`):
- one isolated tenant `p4bench-cycles-N` with a policy and N already assessed, not due members, plus 1 new, due member;
- the rest of the test schema as background: 22,923 members in 10,544 tenants, 181 tenants with a policy.

`EXPLAIN ANALYZE`, MySQL 8.4 local, warm:

| N members in the tenant | Discovery (`dueTenants`) | Rows read (discovery) | Tenant visit (`due`) | Rows read (visit) |
|---|---|---|---|---|
| 1,000 | 3.3 ms | 1,300 member rows | 1.7 ms | 1,001 |
| 10,000 | 16.9 ms | 10,300 | 14.8 ms | 10,001 |
| 100,000 | 182–186 ms | 100,300 | 161–163 ms | 100,001 |

- **Cause:** an unbounded membership scan caused by the data-model access pattern. Being "due" is computed from three conditions, one of them an anti-join, so no index can serve it.
- It is not a missing index on an existing column, not join order, not N+1 and not pagination. Adding an index on `member_cycle_account` could not help the "never assessed" branch.
- Both queries run every second (discovery on every tick, the visit for each due tenant). A single 100k-member tenant cost ≈ 0.35 s of DB time per second even when only one member was due.

## 2. Fix: model the due time explicitly

- `member_record.cycle_due_at DATETIME(3) NOT NULL DEFAULT '1970-01-01'` plus `ix_member_cycle_due(tenant_id, cycle_due_at)` (V38). The value means:
  - **Never assessed:** the epoch default. The member is due as soon as any policy is effective. The default also applies to rows inserted outside the service (seeds, tests).
  - **After an assessment:** `min(cycle end, next policy's effective_from)`, written in the assessment transaction by every path (lane, admin evaluate, growth transaction).
  - **Closed member:** `9999-12-31`, never due. The column is `NOT NULL` on purpose: `NULL` would sort first in the index and turn the O(1) discovery probe back into a scan.
- **Policy changes are rolled out in bounded batches.** `member_cycle_policy.rolled_out` and `rollout_cursor` are new columns. The rollout pulls every member's `cycle_due_at` down to the new policy's `effective_from`:
  - it runs in 500-member primary-key ranges, one short transaction per batch, as a lane item;
  - the cursor advances by compare-and-set, so two instances cannot corrupt it.
  - This replaces the old `policy_version <> current` branch without a whole-tenant `UPDATE` in the publish transaction.
- **Migration:** existing members get the epoch, so each is re-assessed once. Assessment is idempotent: it only writes the snapshot or events when something changed. Existing policies are marked rolled out.

## 3. After (`cycles-query-after.txt`, `cycles-query-after-100k-repeat.txt`)

The same dataset. Assessed members have a future `cycle_due_at`; one member is new.

| N | Discovery | Rows read per policy tenant | Visit | Rows read (visit) |
|---|---|---|---|---|
| 1,000 | 0.51 ms | 1 index entry | 0.02 ms | 1 |
| 10,000 | 0.54 ms | 1 | 0.02 ms | 1 |
| 100,000 | 4.7–4.9 ms | 1 | 1.6–1.7 ms | 1 |

- Discovery cost is now proportional to the number of **tenants with a policy** (227 in the test schema), not to the number of members.
- The visit cost is proportional to the number of **due** members.
- At 100k the per-probe time is higher (0.02 ms vs 0.0014 ms). The rows read stay at 1; the remaining time is page access on a larger index, not a scan.

### Two intermediate attempts that did not work (kept as evidence)

| Probe shape | Result at 100k | Why |
|---|---|---|
| `EXISTS(SELECT … WHERE tenant=p.tenant AND cycle_due_at<=now AND not blocked)` | 33–39 ms (`cycles-query-after-exists-semijoin.txt`) | The optimizer turns `EXISTS` into a semi-join with weedout. In a dependent context it uses only the tenant equality (`ref`) and reads every member. |
| scalar `(SELECT 1 … ORDER BY cycle_due_at LIMIT 1)` | 33 ms | still `ref` on the tenant, followed by a sort of all its rows |
| `(SELECT cycle_due_at … ORDER BY tenant_id, cycle_due_at LIMIT 1) <= now` | **0.007 ms per probe**, 1 row (`cycles-probe-variants.txt`) | the ORDER BY names the full index prefix, so the optimizer recognizes index order and reads one entry |

The chosen discovery reads the earliest `cycle_due_at` of each policy tenant. Blocked members (backoff or quarantine) are excluded in the visit query, not in discovery (see §4).

## 4. Query performance gate (`CycleScheduleTest.criticalDiscoveryQueriesKeepTheirIndexPlans`)

- The test runs `EXPLAIN FORMAT=JSON` on the **real mapper SQL**, bound through MyBatis. It asserts:
  - cycles `due` and `dueTenants` use `ix_member_cycle_due` on `member_record`;
  - points `due` and `dueTenants` use `ix_point_tenant_expiry`;
  - no base table is accessed with `ALL`.
- This prevents a regression back to a full tenant or member history scan. The gate found the missing `FORCE INDEX` on the points visit query (see 02).
- It deliberately does **not** assert absolute latency, which varies by environment. The measured plans, rows read and the dataset scripts are the operational record.

## 5. Behaviour tests (`CycleScheduleTest`, 5 tests)

- A member of a tenant without a policy is never discovered. A policy makes the member due (epoch). After the assessment `cycle_due_at` equals the cycle end, and the member is no longer due.
- A closed member moves to `9999-12-31` and gets no snapshot.
- Policy rollout: 1,201 assessed members due in 20 days; a new policy is effective now → rollout completes (cursor `m01200`) → every member is either due by the effective time or re-assessed under version 2, and eventually all 1,201 have version 2.
- A future policy caps the next due time at its `effective_from`.
- The plan gate described in §4.

## 6. Limitations

- A tenant whose earliest-due member is in backoff or quarantined is still discovered each tick. Its visit costs O(blocked members of that tenant) and does no work.
- Rollout of a new policy for a very large tenant takes (members / 500) lane items. At ≤ 10 per visit (the quantum), 1M members need ≈ 200 visits. Members are re-assessed at the new policy's start once their batch has been pulled forward. For a policy effective *immediately*, the last members can be re-assessed a few minutes late; for a future policy there is no delay.
