# P3 — Database / Index Results

Raw plans: `query-plans.txt`. They are reproducible with the scratch script described below. The dataset was seeded under `qp-*` tenants in `commerce_test_20260923` and deleted after measurement.

- Dataset:
  - `qp-big`: 60,000 completed orders, 60,000 paid payments and 60,000 zeroed point lots (history), plus a few due items.
  - 2,000 `qp-w*` tenants: 20,000 expired unpaid orders, 20,000 due payment checks, 20,000 expired active lots.
  - 200 small tenants with 1 expired order each.
- **BEFORE** = the same statement with the new index ignored (`IGNORE INDEX`), i.e. the plan before V37.
- **AFTER** = the SQL shipped in the mappers.

## 1. Findings that required an index (all measured, none speculative)

With a realistic skew (one tenant with a long history), MySQL's uniform-distribution estimate picks the PRIMARY key `(tenant_id, …)`. It then walks the big tenant's **entire history**, both for the per-tenant due query and for tenant discovery. This cost grows with history, not with backlog, and the rotation now runs these queries on every tenant visit.

| Query | BEFORE | AFTER | Rows examined before → after |
|---|---|---|---|
| orders `expiryDue(qp-big)` | 16.2 ms (PRIMARY lookup, 60,030 rows) | **0.11 ms** (`ix_order_tenant_expiry` range) | 60,030 → 30 |
| orders `expiryTenants` from cursor `''` | 15.8 ms (PRIMARY range, 60,123 rows) | **0.21 ms** (union per status, forced index) | 60,123 → ~50 |
| payments `due(qp-big)` | 17.0 ms | **0.05 ms** | 60,005 → 5 |
| payments `dueTenants` from `''` | 18.6 ms | **0.87 ms** | 60,526 → ~500 |
| points `due(qp-big)` | 69.5 ms (`idx_point_lot_expiry`, all expired lots of every tenant ≤ tenant) | **0.05 ms** | all expired → 5 |
| points `dueTenants` from `''` | 15.7 ms | **0.81 ms** | 60,669 → ~500 |

The scans before V37 are the **pre-Phase-3 plans as well**: the old per-tenant `due`/`expired` queries had the same shape. The rotation only makes them more frequent.

## 2. Changes (V37, additive only)

```sql
ALTER TABLE order_record     ADD KEY ix_order_tenant_expiry(status,tenant_id,expires_at);
ALTER TABLE payment_attempt  ADD KEY ix_payment_tenant_check(status,tenant_id,next_check_at);
ALTER TABLE payment_refund   ADD KEY ix_refund_tenant_check(status,tenant_id,next_check_at);
ALTER TABLE member_point_lot ADD KEY ix_point_tenant_expiry(active_balance,tenant_id,expires_at);
```

- The status/flag column comes first, following the Phase 2 `ix_event_tenant_due(status,tenant_id,available_at)` pattern.
  - Discovery scans one status along tenant order and stops after `limit` distinct tenants.
  - The per-tenant query becomes a tight `(status, tenant, due)` range.
- **Index hints (a deliberate exception to Phase 2's "no hints").** Discovery for orders and payments is written as a `UNION` of one branch per status with `FORCE INDEX`; refunds and points use `FORCE INDEX` directly.
  - Without the hint the optimizer still chose PRIMARY in every probe: plain query, union, and each index shape tried (`tmp_probe_*`, see the session log).
  - The per-tenant queries pick the new indexes without hints.
  - The hint names are part of V37, so renaming an index requires changing the mapper (documented in the XML comment).
- **Existing indexes kept.**
  - `ix_order_expiry`, `ix_payment_check` and `ix_refund_due` still serve the global backlog queries (`expiryBacklog`, `checkBacklog`), which range over due time. They are also used by the admin paths.
  - Redundancy with the new indexes was not proven, so nothing was dropped.
- **Write cost.** One extra secondary index per table on status/due changes. These are small rows (3 short columns + PK).
- V36 adds only nullable or defaulted columns and check constraints. `MODIFY role VARCHAR(16→32)` stays in the same length-byte class.

## 3. Queries measured and left unchanged

| Query | Plan | Cost | Note |
|---|---|---|---|
| orders `expiredLock` | PK point lookup | < 0.001 ms | claim inside the per-order tx |
| orders global backlog (`expiryBacklog`) | `ix_order_expiry` range over expired unpaid | 23.8 ms with a 20k global backlog | read only by health/metrics, cached 5 s, at most once per minute from the scheduler |
| refunds `tenants` (forced) | `ix_refund_tenant_check` range, status `UNKNOWN` | 13 ms, cold, 119 rows | bounded by UNKNOWN refunds, including 115 exhausted ones; not by history |
| events `freshTenants` + `attempts=0` filter | `ix_event_delivery` range (last 10 s) | 0.10 ms | the filter is evaluated on the recent range only |
| cycles `dueTenants` | join over `member_record` of tenants with a policy | 14.8 ms, 0 rows, ~2,300 test tenants | **limitation:** grows with total members of tenants that have a cycle policy. Not changed; needs a due-date column/index on the account model (see limitations). |
| segments/journeys/deliveries/catalog discovery | unchanged SQL (limit parameterised) | – | not re-measured: same shape as before, `LIMIT 4 → 50` per batch |
