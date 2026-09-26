# P3.7 — Retention Analysis and Proposal

Status: **ANALYSED; implementation DEFERRED.** No retention period is required by any product or legal document in the repository. The deletion horizon is a product/legal decision (Stop Condition §29), so nothing is deleted in Phase 3. The engineering constraints below are what any future job must respect.

## 1. Evidence

| Table | commerce_test | commerce_local | Readers in `src/main` |
|---|---|---|---|
| `platform_event` | 32,653 rows | 104 | `EventMapper` only: dispatcher (PENDING range), admin list (by tenant, paged), tenant/global health (PENDING+ISOLATED), retry (ISOLATED/SKIPPED) |
| `platform_inbox` | 35,247 rows | 120 | dispatcher dedup (`INSERT IGNORE` by `(consumer_id,event_id)`), tenant health latency (last 100 per tenant via `ix_inbox_tenant`) |
| `platform_command` / `platform_audit` | 64,216 / 64,216 | 399 / 399 | idempotent replay by `(tenant,actor,operation,key)`; the audit trail |

`platform_event` plus `platform_inbox` take 50.7 MB (data + indexes) in the test schema. Growth is one event per state change plus one inbox row per (consumer, event).

Nothing reads DELIVERED or SKIPPED events for scheduling:
- Every scheduling and health query is bounded to `status IN ('PENDING','ISOLATED')` through `ix_event_delivery` / `ix_event_tenant_due` (Phase 2 plans, V35).
- History therefore affects storage and the paged admin list, not dispatch latency.

## 2. Constraints by state

| State / data | Can it be deleted? | Why |
|---|---|---|
| `PENDING` | **Never** by retention | Undelivered work. |
| `ISOLATED` | **Never automatically** | Awaiting operator decision. Its inbox rows are needed: a retry must skip the consumers that already succeeded. |
| `SKIPPED` | After the replay window | Only retained for audit and explicit replay (B1). |
| `DELIVERED` | After the replay/debug window | Terminal. The retry command rejects DELIVERED rows, so a delivered event can never run again. |
| inbox rows of a DELIVERED event | **Together with the event** (never before it) | Dedup is only needed while the event can still be re-attempted. Deleting an inbox row of a still-PENDING or ISOLATED event would re-run an effect. |
| inbox rows of PENDING/ISOLATED events | **Never** | Exactly-once protection for partial success. |
| `last_error` / `failure_class` / timestamps | With the row | Contains no payload or personal data. |
| `platform_command` / `platform_audit` | **Product/legal decision** | Idempotent replay window and audit obligations. `response_json` may contain business data (R1 finding). Not touched. |
| `payload_json` | With the row | "Minimal payload" by design (no address), but still business data. |

Referential integrity: there is no FK between `platform_inbox` and `platform_event`. A job must delete the inbox rows of a terminal event in the same transaction, or first, to avoid orphans.

## 3. Proposed operationally safe default (not implemented)

```text
DELIVERED events older than 30 days   -> delete event + its inbox rows, batch 1,000, same tx, SKIP LOCKED
SKIPPED events older than 90 days     -> delete (no inbox rows exist)
ISOLATED / PENDING                    -> never deleted automatically
commands / audit                      -> untouched until a product/legal retention decision
```

- 30 days is a proposal. It covers month-end reconciliation and debugging windows that are common in commerce operations. It is **not** a legal obligation and must be confirmed by the product owner.
- **Index support.** A cleanup scan by `(status, created_at)` is not served by the existing indexes. `ix_event_delivery(status,available_at,event_id)` can serve `status='DELIVERED' AND available_at < cutoff`, because `available_at` is the last attempt time and is a safe upper bound. No new index is needed for a first version (to be confirmed with EXPLAIN when implemented).
- **Lane placement.** The cleanup would be an 11th lane on the shared scheduler pool, with its own budget (for example ≤ 1,000 rows or 500 ms per run) and `LANE_*` health codes. It would get the same cross-lane fairness as every other lane.

## 4. Decision needed

| Question | Owner |
|---|---|
| Replay/debug window for DELIVERED and SKIPPED events | product/operations |
| Legal retention of command responses and audit rows | legal/compliance |
| Whether archived events must be exported (cold storage) before deletion | product/operations |
