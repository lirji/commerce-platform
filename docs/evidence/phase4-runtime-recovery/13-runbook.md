# Background Runtime Runbook (Phase 4 edition)

This runbook extends `phase3-background-runtime/13-runbook.md`. Procedures that did not change are referenced, not repeated.

Scope:
- 12 lanes: payments, refunds, orders, events, segments, journeys, cycles, points, deliveries, catalog-jobs, **replay**, **retention**;
- the operator recovery API.

## 0. Where to look

| Need | Source |
|---|---|
| all lanes, cross-tenant | `GET /v1/platform/runtime` (`PLATFORM_OPERATOR`): `events`, `lanes.<lane>.{rotation,backlog,schedule}`, **`replay`** (executed / alreadyProcessed / failed / blocked / yielded), **`retention`** (stats + `lag[]`), `alerts` |
| one tenant's stopped work | `GET /v1/admin/runtime/stopped?workType=event\|order.expiry\|member.points.expiry\|member.cycle.assessment[&failureClass=]` (tenant ADMIN) |
| recovery history | `GET /v1/admin/runtime/recoveries[?workType=&workId=]` |
| replay | `GET /v1/admin/runtime/replay/classifications`, `POST …/replay/dry-run`, `GET …/replays[/id]` |
| logs | `background runtime alert codes=[…]`, `event runtime alert codes=[…]`, `lane dependency breaker open lane=…`, `<lane> item retry\|quarantined tenant=… item=… failureClass=…`, `REPLAY_BLOCKED …`, `replay item failed job=… event=…`, `retention run failed failureClass=…` |

## 1. Event backlog: which of the five situations is it?

| Situation | Signature | Action |
|---|---|---|
| healthy load | `due` high but `delivered` rising every minute; breaker closed; rotation < 5 min; `transientRetrying≈0` | none. Watch `oldestDueAgeSeconds` fall. |
| starvation | `LANE_STARVATION:events`, or `schedule.lastStartLagMillis` large while `due>0` | another lane holds every scheduler thread: find the lane with a long `lastDurationMillis`. Check `commerce.worker-threads` against the pool (Phase 3 §4). |
| dependency outage | `EVENT_DEPENDENCY_UNAVAILABLE` / `LANE_DEPENDENCY_UNAVAILABLE`; `transientRetrying` up; `attempts` not growing | fix the DB, pool or channel. **Do not retry items.** They resume within ≤ 1 min after recovery. |
| poison workload | `EVENT_QUARANTINE_GROWTH` or `EVENT_FAILURE_RATE`; a few events with non-transient `failureClass` and growing `attempts`; `isolated` growing | §4 (inspect → fix → recover). Healthy events keep flowing. |
| no-consumer configuration | `EVENT_NO_REQUIRED_CONSUMER`, `unrouted>0` | deploy the consumer, or declare the type as intentionally unconsumed (Phase 3 B1). Never delete the rows. |

## 2. Breaker open: which dependency, which lane, how long

- **Which lane:** `lanes.<lane>.rotation.breakerOpen=true`, or the log line `lane dependency breaker open lane=<lane> failureClass=<class> cooldownMs=<n>`.
- **Which dependency:** the `failureClass`:
  - `DEPENDENCY_UNAVAILABLE` means the DB or a channel is down;
  - `CONCURRENCY_RETRYABLE` means lock contention;
  - `TRANSIENT` means timeouts.
  - Payments and refunds point at the channel, the other lanes at the DB.
- **How long:** the `breakerTrips` delta per minute. The cool-down doubles from 5 s to 1 min, so trips ≈ minutes of outage.
- **Recovery status:** the breaker closes on the first success. If a lane stays open while the dependency is healthy, check `LANE_STARVATION` and that lane's `lastFailureClass`. Restarting the application resets breakers, but restarting is not needed.

## 3. Dependency outage (unchanged from Phase 3)

No item changes state during an outage. Lanes resume within ≤ 1 min. Do not bulk-retry, and do not restart unless `LANE_STARVATION` persists after the DB is healthy.

## 4. Quarantine: inspect → classify → recover / skip → escalate

1. **Inspect:** `GET /v1/admin/runtime/stopped?workType=<type>`. Read `failureClass`, `lastError` (`CLASS:ExceptionType[/Code]` or `consumer:Type`), `attempts`/`transientAttempts` and `firstFailedAt`/`lastFailedAt`.
2. **Classify.**
   - `BUSINESS_REJECTED`, `DATA_CORRUPTION`, `CONFIGURATION_ERROR`, `PERMANENT` or `UNKNOWN`: the input or the code is wrong. **Fix it first**, because recovering unchanged input fails again (and after 5 more failures it is quarantined again).
   - A transient class with `transientAttempts≈300`: the dependency was down for about a day. Confirm it is healthy.
3. **Decide.**
   - `RETRY` puts the work back into automation:
     - events: only consumers without an inbox row run;
     - points and cycles: the item is READY, or `RESOLVED` if another path already finished it;
     - orders: the expiry counters are reset.
   - `SKIP` exists **only for events**. It means "never run the remaining consumers". Use it only when the business confirms the effect is unwanted, because consumers that already committed keep their effect.
4. **Execute.** `POST /v1/admin/runtime/recoveries`, header `Idempotency-Key`, body `{"workType":…,"action":"RETRY","workIds":[… ≤ 50],"expectedFailureClass":"<class seen in step 1>","reason":"<ticket/why>"}`.
   - Read each outcome. `REJECTED` codes:
     - `NOT_STOPPED`: already recovered or finished;
     - `FAILURE_CLASS_MISMATCH`: it failed differently since you looked; go back to step 1;
     - `STATE_CHANGED`: a concurrent change, or no consumer exists for the event type.
   - Re-sending the same key is safe.
5. **Verify.** Watch the lane: the item disappears from `stopped` and the side effect happens once (for example one `EXPIRE` ledger row for a lot).
6. **Escalate** when the same class keeps coming back after recovery (a code defect), or when stopped orders or payments are older than 1 h (inventory or money is held). Legacy single-item endpoints still work and are audited: `/admin/events/{id}/retry`, `/admin/orders/{id}/expiry/retry`, plus the segment, journey, delivery and catalog-job controls.

## 5. Replay: dry run → validate → execute → observe → pause / abort

1. `GET /v1/admin/runtime/replay/classifications`. Only consumers whose `unprocessed.allowed` (or `reprocess.allowed`) is true can be replayed. Today that is `marketing-effects-v1` only. Every other consumer is `REPLAY_NOT_SUPPORTED`, and the `detail` field shows the reason (financial, benefit or external effect). **Use recovery for their unfinished work.**
2. **Dry run:** `POST /v1/admin/runtime/replay/dry-run` `{consumer, eventTypes, from, to, mode, maxEvents?}`. Check `gate.allowed`, the `byType` counts, `alreadyProcessed`, `wouldExecute` and `capped`. Nothing changes.
3. **Execute:** `POST /v1/admin/runtime/replays` with an `Idempotency-Key` and `{jobId, …, reason}`.
   - The job starts `RUNNING`.
   - `UNPROCESSED` executes only events the consumer never processed. `REPROCESS` re-runs a pure projection.
4. **Observe:** `GET /v1/admin/runtime/replays/{id}` (`examined`, `executed`, `alreadyProcessed`, `failed`, `lastError`, `status`). Platform-wide: `commerce.replay.*` and `lanes.replay`. The job advances by ≤ 100 events per second per instance, and pauses by itself while live events are backlogged (`commerce.replay.yielded`).
5. **Pause / resume / cancel:** `POST /v1/admin/runtime/replays/{id}/control` `{"action":"PAUSE"|"RESUME"|"CANCEL","expectedVersion":<version>,"reason":…}`. Resume re-checks the safety gate. A `FAILED` job (10 consumer failures, or `GATE:*`) is terminal: fix the cause and create a new job. Events already handled are skipped by the inbox.
6. `REPLAY_BLOCKED` alert: someone attempted an unsafe replay, or a deployment changed a consumer's classification during a job. Check the audit and the classifications. It is not an incident unless it was unexpected.

## 6. Retention failure: safe to defer, storage risk, or integrity risk?

Retention is **off by default** (a product/legal decision is pending). When enabled:

| Signal | Classification | Action |
|---|---|---|
| `RETENTION_FAILURE` with `lastFailureClass` transient (`DEPENDENCY_UNAVAILABLE`, `CONCURRENCY_RETRYABLE`, `TRANSIENT`) | **safe to defer**: nothing was deleted by the failed batch (it rolled back), and the next run continues | fix the DB or lock contention; no data action |
| `RETENTION_LAG_HIGH`, lag growing day over day, runs yielding (`yielded` rising) | **storage-risking**: growth exceeds the cleanup capacity, or live backlog keeps it yielding | check `commerce.retention.purged` per run and the live backlog; consider a longer retention, a larger window, or running off-peak. Never raise the batch size above 500 without an EXPLAIN review. |
| `IllegalStateException` "删除数与锁定数不一致" in the log, or `lastFailureClass=UNKNOWN`/`CONFIGURATION_ERROR` | **possible integrity risk**: the fail-safe check stopped a batch whose delete count differed from its lock count | disable retention (`commerce.retention.enabled=false`) and investigate before re-enabling. Nothing was deleted by that batch. |
| event rows missing that were inside an active replay range | **integrity defect**; cannot happen by design (the cutoff is capped at the earliest RUNNING/PAUSED replay start) | disable retention and escalate |

## 7. Capacity knobs

See `14-performance-analysis.md` and `16-capacity-soak.md`. Recommendations:
- Prefer more application instances over intra-lane parallelism (Phase 3 decision, unchanged).
- Keep `commerce.worker-threads` ≤ pool − 5.
- Replay and retention budgets are constants; change them only with a benchmark.
