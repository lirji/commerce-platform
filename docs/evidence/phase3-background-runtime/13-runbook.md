# Background Runtime Runbook

Scope: the 10 background lanes started when `commerce.workers-enabled=true`. All alert codes are WARN log lines with a fixed code. They contain no tenant or payload data, except `lane visit failed` lines, which carry the tenant id for diagnosis. External alert delivery is not wired: route by log code.

## 1. Where to look

| Need | Source |
|---|---|
| All lanes at once (cross-tenant) | `GET /v1/platform/runtime` with a `PLATFORM_OPERATOR` token. It returns `events`, `lanes.<lane>.{rotation,backlog,schedule}` and `alerts`. |
| One tenant's events | `GET /v1/admin/events/health`, `GET /v1/admin/events` (tenant ADMIN). These carry `failureClass`, `attempts`, `transientAttempts`, `firstFailedAt`, `lastFailedAt`, `lastError`, `skipReason`, `manualRetries`. |
| Metrics | `commerce.events.*` (no tags), `commerce.lanes.*{lane}`. The HTTP registry is not exposed. Read the platform endpoint or attach a registry. |
| Logs | `event runtime alert codes=[…]` (events, per minute), `background runtime alert codes=[…]` (lanes, per minute), `lane dependency breaker open lane=… failureClass=… cooldownMs=…`, `event quarantined …`, `order expiry quarantined …`, `worker lane failed lane=… failureClass=… errorType=…` |

Configuration: `commerce.worker-threads` (default 3, allowed 1–6). Each thread holds at most one DB connection. Keep `threads ≤ hikari.maximum-pool-size − 5`.

## 2. Alert codes

| Code | Definition / trigger | Clears when | Severity | Operator action |
|---|---|---|---|---|
| `LANE_STARVATION:<lane>` | The lane has waited to start > 30 s (idle since its last finish, or its last start was > 30 s late). | The lane runs again on time. | **High** | Check `schedule.lastDurationMillis` of the other lanes: something is holding every scheduler thread. Look for a lane with a long run (a slow dependency beyond its budget). Raising `commerce.worker-threads` is a mitigation only within the DB pool limit. |
| `LANE_DEPENDENCY_UNAVAILABLE:<lane>` / `EVENT_DEPENDENCY_UNAVAILABLE` | The lane breaker is open, or it opened since the last sample (3 consecutive transient failures). | The first successful item after the cool-down. | High | Check DB connectivity or pool, the payment/refund channel, and lock contention. **No action on items is needed.** Transient failures do not consume poison budgets. Do not bulk-retry. |
| `LANE_BACKLOG_AGE:<lane>` | The oldest due item in `orders`/`payments`/`refunds` waited > 5 min. | Oldest due item < 5 min. | Medium | Look at `rotation.lastRotationMillis` and `schedule`. A backlog with a healthy breaker means capacity. A backlog with an open breaker means a dependency problem. |
| `LANE_QUARANTINE_GROWTH:<lane>` | The count of stopped items grew between samples: order expiry stopped, or automatic payment/refund checks exhausted. | No new stopped items. | Medium | See §3 for the lane. |
| `LANE_ROTATION_SLOW:<lane>` | A full tenant rotation took > 5 min, i.e. a tenant's worst wait. | The rotation is faster. | Medium | A large tenant count with due work, or a slow per-item cost. Check the lane budget and the DB. |
| `EVENT_NO_REQUIRED_CONSUMER` | PENDING events exist whose type has no consumer and no `UnconsumedEventType` declaration. | `unrouted = 0`. | High (deployment defect) | Deploy the missing consumer. **Or**, if the type is intentionally unhandled, add a declaration in the publishing module and convert the old rows with a migration. Never delete the rows. |
| `EVENT_BACKLOG_AGE`, `EVENT_ROTATION_SLOW`, `EVENT_BACKLOG_GROWING`, `EVENT_QUARANTINE_GROWTH`, `EVENT_FAILURE_RATE`, `EVENT_NO_PROGRESS` | Phase 2 contract, unchanged. | – | – | See `phase2-event-runtime/PHASE2_REPORT.md` §7. |

- Codes are evaluated once a minute. They never fire on every scheduler tick.
- **A fully stopped scheduler emits no log.** Probe `GET /v1/platform/runtime` externally and alert when `schedule.lastStartedAt` is old.

## 3. Procedures

### Quarantined event (`ISOLATED`)
1. `GET /v1/admin/events` for the tenant. Read `failureClass`, `lastError` (`consumer:ExceptionType[/Code]`) and the counts.
2. `BUSINESS_REJECTED` / `DATA_CORRUPTION` / `CONFIGURATION_ERROR` / `UNKNOWN`: fix the data or the code first. Retrying unchanged input fails the same way.
3. Transient class with `transientAttempts=300`: the dependency was down for about a day. Confirm it is healthy.
4. `POST /v1/admin/events/{id}/retry` with an `Idempotency-Key`. Only consumers without an inbox row run. The evidence is kept and `manualRetries` increases.

### Skipped event (`SKIPPED`)
- It is expected for declared types (`order.fulfilling.v1`).
- To deliver one to a newly added consumer, retry it by id. Types with no consumer are refused.

### Stopped order expiry (`expiry_attempts=5`)
- The order stays unpaid and keeps its holds (inventory, coupon, points, budget).
- Read `expiry_error` (for example `BUSINESS_REJECTED:DomainException/CONFLICT` means an inconsistent hold).
- Fix the hold, then `POST /v1/admin/orders/{id}/expiry/retry`.

### Payment checks exhausted
- For payments: `POST /v1/admin/orders/{id}/payment/reconcile`. Order expiry → `CLOSING` also re-arms the checks automatically.
- For refunds: reconcile by refund id.
- `check_error` tells a channel conflict (`BUSINESS_REJECTED`) apart from other causes. Channel outages no longer exhaust checks.

### Database outage
- Expected behaviour:
  - Lanes log `worker lane failed … DEPENDENCY_UNAVAILABLE`, then the breaker opens and runs are skipped for 5 s → 1 min (doubling).
  - No event or order changes state during the outage.
  - After recovery, each lane resumes within ≤ 1 min (the remaining cool-down); the first success closes the breaker, and the backlog drains through the normal rotation (fresh events first).
- **Do not** bulk-retry.
- **Do not** restart the app to "unstick" it unless `LANE_STARVATION` persists after the DB is healthy.

## 4. Capacity knobs (evidence in `08-performance-and-load.md`)

- `commerce.worker-threads`: cross-lane isolation. Default 3. Lanes do not run faster individually.
- Per-lane `TenantRotation.Policy` constants in each service define the fairness contract. Change them only with a benchmark.
- Parallel dispatch within the event lane (several dispatchers) is **not enabled**. See the P3.6 decision.
