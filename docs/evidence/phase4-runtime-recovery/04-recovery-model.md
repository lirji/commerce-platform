# P4.3 — Quarantine Recovery Model

## 1. Components

| Component | Layer | Responsibility |
|---|---|---|
| `RecoverableWork` (`runtime.api`) | platform API implemented by each owning module | `workType`, allowed `actions`, `stopped(tenant, failureClass, after, limit)`, `find`, and `recover(tenant, id, action, now)`. The last one is a conditional update in the caller's transaction that throws `CONFLICT` without partial writes. |
| `RuntimeRecovery` (`runtime`) | use case | authorization (capabilities), scope limits, the idempotent command transaction, per-item outcome, audit |
| `RecoveryAudit` + `platform_recovery` (V39) | audit | one row per recovery item (applied **or rejected**), written in the same transaction as the state change (`Propagation.MANDATORY`) |
| `RuntimeRecoveryController` (`/v1/admin/runtime/**`) | HTTP | thin: it maps requests to the use case. Authorization, scope, audit and recovery rules are not in the controller (`ModuleBoundaryTest` passes). |

Registered work types:

| workType | Owner | Actions | Stopped state | RETRY effect | SKIP effect |
|---|---|---|---|---|---|
| `event` | platform-runtime (`EventRecovery`) | RETRY, SKIP | `ISOLATED` (RETRY also accepts `SKIPPED` when a consumer now exists) | → `PENDING`: counters reset, evidence kept; only consumers without an inbox row run | `ISOLATED` → `SKIPPED(OPERATOR_SKIPPED)`: consumers that already committed keep their effect; the rest never run |
| `order.expiry` | order-runtime (`OrderExpiryRecovery`) | RETRY | unpaid order with the expiry budget exhausted | counters and backoff reset, last evidence kept | not supported: an unpaid order must not hold inventory forever |
| `member.points.expiry` | member (`ItemRetries`) | RETRY | `quarantined_at` set | counters reset, `retry_at=now`, evidence kept → READY. If the lot no longer needs expiry (expired by another path) → the retry row is removed → `RESOLVED` | not supported (see 01) |
| `member.cycle.assessment` | member (`ItemRetries`) | RETRY | `quarantined_at` set | as points (RESOLVED when the member is no longer due) | not supported |

The module-specific controls that existed before Phase 4 keep their APIs and now write a `platform_recovery` row in the same transaction:
- segment `retry` / `retry-announcement` / `cancel`;
- journey instance `retry` / `cancel`, and journey scan `retry`;
- coupon delivery `RETRY` / `CANCEL` / `REVOKE`;
- catalog job `RETRY` / `CANCEL`;
- event `/admin/events/{id}/retry` and order `/admin/orders/{id}/expiry/retry`.

The legacy endpoints have no reason field; their audit `reason` is `NULL`.

## 2. Workflow: inspect → classify → decide → execute → audit

1. **Inspect:** `GET /v1/admin/runtime/work-types` and `GET /v1/admin/runtime/stopped?workType=…&failureClass=…&after=…&limit≤100`. The response has metadata and failure evidence only: class, `last_error`, both counters, first and last failure time, and manual recoveries. There is no payload.
2. **Classify:** filter by `failureClass` (validated against `FailureClass`).
3. **Decide:** choose explicit `workIds` (1–50) and an action. There is **no "recover all" endpoint** and no filter-based bulk action.
4. **Execute:** `POST /v1/admin/runtime/recoveries` with `Idempotency-Key` and the body `{workType, action, workIds, expectedFailureClass?, reason}`. `reason` is required (≤ 256 characters).
   - `expectedFailureClass` is a guard: if the item's class changed since inspection, that item is `REJECTED/FAILURE_CLASS_MISMATCH` and unchanged.
   - The whole request is one `Commands` transaction (10 s timeout; ≤ 50 conditional updates).
   - Each item gets an outcome: `APPLIED(previousState → newState)` or `REJECTED(code)`. The codes are `NOT_STOPPED`, `FAILURE_CLASS_MISMATCH`, `STATE_CHANGED` and `NOT_FOUND`.
5. **Audit:** `platform_recovery` gets one row per item: tenant, actor, operation, command key, work type and id, action, previous and new state, failure class, reason, result, rejection code, and time. `GET /v1/admin/runtime/recoveries?workType=&workId=` lists them.

## 3. Idempotency boundary

| Path | Boundary |
|---|---|
| recovery command | `platform_command(tenant, actor, operation, key)` plus a request hash. The same key and body return the committed result and write no second audit row; the same key with a different body gets 409 `IDEMPOTENCY_CONFLICT`. |
| state transition | conditional update on the stopped state (`WHERE status='ISOLATED'`, `quarantined_at IS NOT NULL`, `expiry_attempts ≥ max`), preceded by a **locking read** (`FOR UPDATE`) for events and member items |
| work executed after a recovery | the normal lane's own boundaries: event inbox `(consumer, event)`, lot `remaining=0` re-check under the member lock, order status/version CAS |

**Two defects found and fixed while proving multi-instance recovery (10):**
1. Concurrent `RESOLVED` recoveries of the same member item both reported `APPLIED`, because the delete had no compare-and-set. The fix is a locking read plus an affected-row check.
2. For events, a concurrent SKIP and RETRY could audit a stale `previous_state`, because the state was read without a lock. The fix is `lockView … FOR UPDATE` before the conditional update, so the audit chain is exact.

## 4. Failure evidence preservation

Recovery never clears `failure_class`, `last_error`, `first_failed_at` or `last_failed_at`. It resets only the counters and the backoff, and increments `manual_retries`/`manual_recoveries`. This is asserted in `RuntimeRecoveryTest.quarantinedLotRecoveryIsExplicitAuditedIdempotentAndPreservesEvidence`.

## 5. Tests (`RuntimeRecoveryTest`, 5 tests; `MultiInstanceRecoveryTest`, 4 tests)

- The full workflow on a quarantined lot:
  - inspect, filter, recover with the guard, audit row contents;
  - idempotent replay (no second audit row), 409 on the same key with a different body;
  - after fixing the data, the lane expires the lot **exactly once**: `remaining=0`, one `EXPIRE` ledger row, retry row deleted;
  - a second recovery is `REJECTED/NOT_STOPPED` with an audit row.
- Guards:
  - class mismatch leaves the state unchanged;
  - 51 ids, zero ids, duplicate ids or a blank reason → 400;
  - SKIP on points → 400;
  - unknown work type → 404.
- Tenant scope: another tenant's admin cannot see the item, and a recovery attempt from it is `NOT_STOPPED` and audited in the caller's tenant.
- Events:
  - SKIP gives `OPERATOR_SKIPPED` and keeps the evidence;
  - RETRY of a type without a consumer is rejected;
  - the legacy retry endpoint writes an audit row.
- Concurrency: 8 concurrent recoveries → exactly 1 APPLIED, 8 audit rows, `manual_recoveries=1`. The same holds on the RESOLVED path. SKIP/RETRY races produce a valid chain (10 rounds).
