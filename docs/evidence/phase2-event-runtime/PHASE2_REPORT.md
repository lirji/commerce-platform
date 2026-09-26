# Phase 2 — Event Processing Fairness, Backlog Resilience & Operational Readiness

- Date: 2026-09-26 (UTC). Baseline: `main` @ `86d7e0a` + the uncommitted Remediation R1 working tree (`docs/evidence/remediation-r1/`).
- Environment: Java 21, Maven 3.9, MySQL 8.4.11 in Docker on macOS (`innodb_flush_log_at_trx_commit=1`, 128 MB buffer pool), isolated schema `commerce_test_20260923`. Frontend source and `commerce_local` were not modified.
- Final status: **PHASE_2_COMPLETE_WITH_LIMITATIONS**

## 1. Current-state reconstruction (before this phase)

```text
@Scheduled(fixedDelay=1000) EventWorker.deliver()           single scheduling thread, 10 lanes in sequence
  └─ lane("events") → EventDispatcher.tick()                 synchronized per instance
       ├─ tenants(types, cursor)   SELECT DISTINCT tenant_id … status='PENDING' AND due AND type IN(…) AND tenant_id>cursor ORDER BY tenant_id LIMIT 4
       ├─ for each of ≤4 tenants: pending(tenant, types, 5)  ORDER BY available_at,event_id LIMIT 5   (tenant FIFO)
       │    └─ for each consumer of the event: TX(timeout 10s) {
       │           lock: SELECT … FOR UPDATE SKIP LOCKED (status PENDING, due)
       │           inbox: INSERT IGNORE platform_inbox(consumer,event) → handle() only if inserted
       │           last consumer and no failure → UPDATE status='DELIVERED' }
       │    └─ any consumer failed → TX { attempts+1, backoff 2^n s (+0..1s), ISOLATED at 5 }
       └─ cursor = last tenant; empty result → cursor = ""
Manual: POST /v1/admin/events/pump (own tenant, ≤5 events); retry: ISOLATED→PENDING, attempts=0 (audited command).
```

- States: `PENDING`, `DELIVERED`, `ISOLATED` (CHECK constraint). No lease, no claimed_by: a claim is the row lock inside one consumer transaction.
- Indexes: `PRIMARY(event_id)`, `uk_event_fact(tenant_id,event_type,aggregate_id,aggregate_version)`, `ix_event_delivery(status,available_at,event_id)`.
- No cleanup/archival. Failure reason only in WARN logs.
- Capacity: at most 4 tenants × 5 events = **20 events per tick**, and one tick per ≥1 s. A full rotation takes ⌈T/4⌉ ticks, where T is the number of tenants with due events.

## 2. Reproduction (Slice 1)

Harness: `EventSchedulingBenchmarkTest` (opt-in with `-Dcommerce.event-benchmark=true`). Each run uses its own event type and no-op consumers, so the 10k residue is not scheduled but is still scanned by the indexes. Modeled time = measured tick time + 1 s fixedDelay per tick. It excludes the other 9 lanes, so it is a lower bound. The victim is a tenant that sorts just before the cursor and arrives after tick 1: the worst position for a lexicographic rotation.

| Scenario (before) | Ticks | Processing | Modeled drain | Modeled ev/s | Max tenant first-attempt tick | Victim wait |
|---|---|---|---|---|---|---|
| single tenant 10k | 3,999 | 135 s | 69 min | 2.4 | 1 | – |
| balanced 20 × 500 | 599 | 91 s | 11.5 min | 14.5 | 5 | – |
| dominant 90% + 500 small | 3,724 | 118 s | 64 min | 2.6 | 126 | 127 ticks / 131 s |
| many small 2,500 × 4 | 627 | 47 s | 11.2 min | 14.8 | 625 | **626 ticks / 673 s** |

This separates the three concerns:
- Total throughput is capped by the structure (20/tick), not by the database: processing speed is 74–219 ev/s, but modeled throughput is 2.4–14.8 ev/s.
- Fairness is bounded but proportional to tenant count (⌈T/4⌉ ticks).
- Starvation: with 2,500 active tenants a new tenant waits ~11 min. This reproduces the R1 E2E failure (payment event with `attempts=0` behind 10,580 events from 2,539 tenants).

Query cost (`explain-before-index.txt`, a tenant with 20k delivered + 5 pending rows):
- The per-tenant `pending` query scanned 10,304 rows through `ix_event_delivery`: **31 ms per tenant visit**, and it grows with the global backlog.
- Tenant discovery walked 22,264 rows through `uk_event_fact`: **29 ms**, and it grows with delivered history.

## 3. Scheduling contract (Slice 2)

| Question | Answer |
|---|---|
| Can one tenant monopolize the worker? | No. A visit processes ≤5 (quantum) events; the tenant is revisited only after every other due tenant in the rotation. Exception: when it is the **only** tenant with due events, it is drained continuously within the tick budget. |
| FIFO global or per tenant? | Per tenant (`available_at, event_id`). No global FIFO. |
| Max batch per tenant | 5 per visit. |
| Active-tenant discovery | Fresh lane: tenants with events that became due in the last 10 s (`ix_event_delivery` range). Rotation: next 50 tenants after the cursor (`ix_event_tenant_due`). |
| Re-entry | A tenant re-enters the fresh lane whenever an event becomes due (new or retry backoff expiry); otherwise it waits for the next rotation. |
| Can retries monopolize? | No. A retried event occupies one slot per visit, at most 5 times in total (2/4/8/16 s backoff), then it is ISOLATED. |
| Can poison events consume slots forever? | No. There are exactly 5 attempts, then quarantine (tested). |
| What prevents starvation? | Rotation always keeps ≥ half of each tick budget. Every due tenant is attempted within one rotation. |
| Tenant owns 90%+ | It gets 5 per visit like everyone else. Measured: small tenants were all attempted before the dominant tenant's 6th event (tested). |
| New events during a drain | Attempted in the next tick through the fresh lane, if fresh work is < half a tick budget. Otherwise they fall back to FIFO among fresh tenants, then to the rotation. |

**Fairness invariant.** Given N tenants with due events, a healthy tenant is attempted within one full rotation. The rotation length is `Σ min(due_i, 5) × c / duty`, where c is the per-event cost and duty ≈ 50% (1 s budget + 1 s fixedDelay). A tenant whose event just became due is attempted in the next tick, unless more than half a budget of fresh work arrived in the same 10 s window. The observed rotation length is exported as `commerce.events.rotation.last`, and `EVENT_ROTATION_SLOW` fires at > 5 min.

Rejected alternatives:
- Global oldest-first: new events wait behind the entire backlog.
- Least-recently-served: needs a full active-tenant scan every tick.
- Weighted scheduling: no evidence of tenant weights.
- Lease/claimed_by: claims never outlive a transaction; see §6.

## 4. Changes (Slices 3–9)

| Change | Files | Transaction / state impact | Rollback |
|---|---|---|---|
| Time/attempt-budgeted tick: fresh lane first (≤ half budget), then a 50-tenant rotation that wraps until the budget is spent or a full pass makes no attempt; lone-tenant fast path | `EventDispatcher` | Same per-consumer transactions and inbox; no new states | Revert file |
| `Budget(quantum=5, tenantBatch=50, tick=1 s, maxAttempts=2000, freshWindow=10 s)`; tests inject an attempt budget for determinism | `EventDispatcher` | – | – |
| `last_error` (consumer:ExceptionType[/DomainCode], ≤160 chars, no payload or message) written with each failure; quarantine WARN and counter | `EventDispatcher`, `EventMapper.xml` | `failed` UPDATE sets one more column | Column is nullable |
| Index `ix_event_tenant_due(status,tenant_id,available_at)` | `V35__event_scheduling_index_and_failure_evidence.sql` | Additive only; online DDL | `DROP INDEX`, `DROP COLUMN` |
| Legacy quote snapshot decode (`JsonCodec.readLegacy`, `QuoteService.snapshot`) at all 3 read sites | `trade`, `platform-runtime` | Read only | Revert |
| Tenant health `GET /v1/admin/events/health` (ADMIN, own tenant) | `PaymentController`, `EventDispatcher`, `EventMapper` | Read only | Revert |
| Global Micrometer meters `commerce.events.*` (no tags, DB values cached 5 s) | `EventRuntimeMetrics` | Read only; queried only when read | Revert |
| Alert detection: once-per-minute global health log with fixed codes | `EventHealthLog` | One aggregate query per minute | Revert |

Query plans after the index (`explain-after-index.txt`), same 20k-history tenant:
- `pending`: **31 ms → 0.04 ms**
- tenant discovery: **29 ms → 5.7 ms**
- fresh lane: 0.13 ms

No index hints were needed.

A covering, type-less discovery was tried and rejected. Unconsumed `order.fulfilling.v1` rows stay PENDING for every fulfilled order, so that scan would grow with total order history.

## 5. Retry, poison and legacy events (Slices 5–6)

Retry model (unchanged policy, now with evidence):
- Eligibility: PENDING and due.
- Order: per-tenant FIFO by `available_at`.
- Backoff: 2, 4, 8, 16 s (+0–1 s jitter). The 5th failure sets ISOLATED.
- Completed consumers are never re-run (inbox).
- Admin retry resets attempts; only unfinished consumers run.

| Failure | Evidence | Classification | Can it exist in a real DB? | Action |
|---|---|---|---|---|
| N2 `marketing-effects-v1` on `order.created/cancelled` | Probe replay: `MismatchedInputException: Cannot map null into int … QuoteApi$View["coupon"]->CouponApi$Application["platformFundingBps"]`. The failing input is the **stored quote snapshot**, not the event payload. The 7 affected quotes were written 2026-09-23 10:24:48–55 UTC by code at `8c20699`. `33f5993` (10:32 UTC) added the primitive field; V10 backfilled coupon definitions (`DEFAULT 0`) but not snapshots. | **SCHEMA_EVOLUTION + MIGRATION_GAP** | Yes, in any DB that created coupon quotes while running `8c20699` (pushed to remote branches). `commerce_local`: 0 rows. | Decode with the V10 default `0` (merchant-funded), matching the existing `funding==null` rule in effects. Other missing primitives still fail (tested). All 5 affected events were DELIVERED at runtime. |
| N3 `member-cycle-benefit-v1` | `DomainException/CONFLICT` from `EntitlementService.grantFromSource`: the bundle references benefit `exhausted` with quota 1 / issued 1. Rows come from `MemberCycleTest.exhaustedBundleRollsBackEveryGrantAndQuota`, which leaves the assessed event unconsumed. | **BUSINESS_INVARIANT_VIOLATION** (test fixture origin) | Yes: any bundle pointing to an exhausted or out-of-window benefit. The handler comment states failures are kept for operator review. | No code change. It is quarantined after 5 attempts with `last_error=member-cycle-benefit-v1:DomainException/CONFLICT` (43 rows at runtime). Whether a bundle should be granted partially is a product decision. |

Schema audit:
- Event types carry versions (`*.v1`), and most handlers re-read authoritative state by aggregate ID.
- Jackson 3 in `JsonCodec` ignores unknown properties but fails on missing primitives. Adding a required primitive field to a payload record therefore quarantines older rows.
- 8 payload decode sites were checked. The only proven gap is the quote snapshot above.
- No generic upcaster framework was added (not required, per the evidence).

## 6. Claiming and concurrency

- A claim is `SELECT … FOR UPDATE SKIP LOCKED` inside one consumer transaction (timeout 10 s; `innodb_lock_wait_timeout=5`).
  - Crash after claim: rollback releases the lock; the event stays PENDING.
  - Crash after a committed consumer: its inbox row makes the retry skip it.
  - Crash before the state update: the delivered mark commits atomically with the last consumer.
  - Double claim: SKIP LOCKED makes the second worker skip.
  - The delivered/failed updates are guarded by `status='PENDING'`.
- **Proven, not assumed:** `concurrentWorkersNeverProcessAConsumerTwice` runs 4 dispatcher instances in parallel over 500 events × 2 consumers. Result: 1,000 handler calls, 1,000 inbox rows, every (consumer, event) exactly once, 0 left PENDING.
- A lease or `claimed_by` is **not** introduced: no claim outlives a transaction, so there is nothing to expire.
- Limitation: instances do not coordinate their cursors, so discovery work is duplicated across instances.

## 7. Observability and alert contract

| Signal | Source |
|---|---|
| queue depth / pending / retrying / quarantined / unrouted / oldest due age | `commerce.events.queue.depth`, `.pending`, `.retrying`, `.quarantined`, `.unrouted`, `.oldest.due.age` (global, no tags) |
| throughput / failure rate / latency | counters `.attempted`, `.delivered`, `.consumer.failures`, `.quarantined.total`, `.latency.total` (÷ delivered = mean end-to-end ms) |
| tenant starvation bound | `.rotation.last` (s), `.rotation.tenants` |
| tenant depth / oldest age / latency | `GET /v1/admin/events/health` (own tenant; `recentMaxLatencyMillis` over the last 100 inbox rows) |
| quarantine evidence | `platform_event.last_error`, `GET /v1/admin/events` (`lastError`) |

Alert codes are WARN lines from `EventHealthLog`, evaluated once a minute; the rules are unit tested:

| Code | Condition |
|---|---|
| `EVENT_BACKLOG_AGE` | oldest due event > 300 s |
| `EVENT_ROTATION_SLOW` | full rotation > 300 s |
| `EVENT_BACKLOG_GROWING` | due count grew for 5 consecutive minutes |
| `EVENT_QUARANTINE_GROWTH` | ISOLATED count increased |
| `EVENT_FAILURE_RATE` | ≥ 20 attempts and > 20% consumer failures |
| `EVENT_NO_PROGRESS` | due > 0 in two samples with no attempts |

- A fully stopped scheduler emits no log. It must be probed externally (health endpoint, `.attempted` counter).
- External alert delivery is **deferred**: there is no provider.
- The runtime log shows `EVENT_BACKLOG_AGE` while the residue drained, `EVENT_QUARANTINE_GROWTH` as N3 rows were isolated, and then INFO health (`runtime-health-log.txt`).

## 8. 403 / 404 contract decision

**Decision: keep the current behavior and document it.**
- Callers outside a namespace they may enter get 403 (anonymous: 401), whether or not the path exists.
- Inside a permitted namespace, a missing route is 404.

Reasons:
- It avoids endpoint enumeration through status codes.
- It is how Spring Security's `anyRequest().denyAll()` behaves before handler resolution.
- A 404-for-missing design would require resolving routes ahead of authorization and would leak route existence.
- R1 found no client dependency on the old 404.

Regression test: `AuthorizationCoverageTest.statusDistinguishesPermittedNamespaceNotRouteExistence` covers anonymous 401, member 403 on unknown and existing admin paths, admin 404 under `/v1/admin`, member 404 under `/v1/members/me`, and admin 200 on the health endpoint.

## 9. Performance results (same machine and harness)

| Measure | Before | After (burst backlog) | After (aged backlog) |
|---|---|---|---|
| 300 events, 1 consumer: processing / modeled ev/s | 1,535 ms / 12.2 | 1,673 ms / 81.7 | 1,519 ms / 85.3 |
| 300 events, 3 consumers | 3,796 ms / 11.2 | 4,297 ms / 32.3 | 3,971 ms / 37.6 |
| 10k single tenant, modeled drain | 4,134 s (2.4 ev/s) | 152 s (65.9) | 147 s (68.3) |
| 10k dominant 90%, modeled drain | 3,842 s | 148 s | 147 s |
| 10k many small, modeled drain | 674 s | 102 s | 88 s |
| New tenant behind 10k many-small: time to first attempt | 626 ticks / 673 s | 10 ticks / 20 s | **1 tick / 2.0 s** |
| New tenant behind 10k dominant | 127 ticks / 131 s | 5 ticks / 10 s | **1 tick / 2.0 s** |
| Max tenant first-attempt tick, many-small 10k | 625 | 56 | 44 |

"Burst" means the whole backlog became due inside the 10 s fresh window, so fresh arrivals compete FIFO. Real historical backlogs are aged.

Consumer cost profile (Section 18), measured with a throwaway probe:

| Operation | Cost |
|---|---|
| Round trip | 0.71 ms |
| Empty transaction | 2.08 ms |
| Insert + commit | 5.53 ms |
| Lock-select + inbox insert + commit | 7.73 ms |
| Measured cost per extra consumer | ≈ 4.1 ms |

The extra cost comes from commit/fsync and round trips, not dispatch or serialization. Isolation was not weakened. Bounded parallelism across tenants is the remaining lever; it is deferred (§12).

Real residue at runtime (packaged jar, workers on, test schema; `runtime-drain.txt`):

| Time (UTC) | Due events | Tenants with due events |
|---|---|---|
| 04:12:40 | 10,333 | 2,272 |
| 04:14:31 | 4,604 | 66 |
| 04:16:22 | 0 | – |

- Real-handler throughput was ≈ 45–70 ev/s. The count at 04:16:22 excludes one test-only unrouted event.
- Figures come from local Docker MySQL on macOS with no-op consumers in the harness. They are not production capacity.

## 10. Tests

| Kind | Result |
|---|---|
| Full backend `scripts/verify.sh` | **BUILD SUCCESS: 244 run, 243 passed, 0 failed, 1 skipped** (the opt-in benchmark). Before: 230/230. `verify-final.log` |
| New: `EventSchedulingFairnessTest` (6) | Fresh tenant is attempted next tick behind a 300-tenant backlog; rotation bound ≤ 19 ticks; dominant tenant ≤ 1 quantum before others; single-tenant budget; poison quarantine with evidence, siblings not re-run, controlled retry; legacy/malformed payload quarantine; 4-worker concurrency. |
| New: `LegacyQuoteSnapshotTest` (2), `EventHealthAlertTest` (2), `EventRuntimeObservabilityTest` (2), `AuthorizationCoverageTest` +1 | Pass |
| Mutation | With the fresh window set to 0, the victim test fails (`expected <2> but was <null>`). |
| Regression kept green | Consumer isolation, lane isolation, worker resilience (expiry, CLOSING re-arm), deadlock lock order, ModuleBoundaryTest (3), all business suites. |
| Browser E2E, backlog spec | `commerce.spec.ts` 4/4 passed while 6,538 historical events were still due (previously failed on starvation). |
| Browser E2E, full | **17/21** (baseline 16/21). |

The 4 remaining E2E failures are unrelated to the backend. They were confirmed from the error text and current source:

| Spec | Failure | Cause |
|---|---|---|
| `catalog-merchandising` | member shop filter locator times out | Uncommitted Shop rework |
| `member-behavior` | `查看商品` button no longer exists | Uncommitted `Shop.tsx` (HEAD has it, the worktree does not) |
| `member-cycles` | `color-scheme` expected dark, received light | Uncommitted `style.css` sets `color-scheme: light` |
| `coupon-deliveries` | expects `撤销 2`, UI renders `撤销 / 保留 2 / 0` | Pre-existing spec vs. committed UI mismatch |

## 11. Known limitations

1. The fresh-lane priority holds only while fresh work per tick is below half the budget. Under a burst it degrades to FIFO among fresh tenants (measured: 20 s behind a 10k burst), then to the rotation bound.
2. Single scheduling thread. Under backlog, the events lane uses up to ~1 s per cycle, so the other 9 lanes run about every 2 s+ instead of ~1 s.
3. The other lanes (orders expiry, payments, refunds, segments, journeys, coupon deliveries, catalog jobs) still use the 4-tenant cursor pattern. Not changed: out of scope.
4. `order.fulfilling.v1` has no consumer and stays PENDING forever. It is excluded from scheduling, depth and alerts, but still occupies the PENDING index range (§12, B1).
5. The retry budget (5 attempts in ~30 s) does not distinguish transient from permanent failures. A database outage longer than ~30 s quarantines healthy events, which then need manual retry.
6. Metrics are registered but not exposed over HTTP (§12, B2). In-process statistics are per instance.
7. A tick can overrun its budget by one consumer transaction (≤ 10 s timeout).
8. The test schema keeps accumulating residue, because each test run creates new tenants.

## 12. Blocked / deferred

**B1 — Unconsumed event types (`order.fulfilling.v1`)**
- *Why it blocks:* the fix is a product or architecture decision about whether a future consumer (e.g. a WMS) must receive historical rows.
- *Evidence:* 391 rows in the test schema and 8 in `commerce_local`, all PENDING with 0 attempts. There is one per fulfilled order, so this grows with order volume.
- *Options:*
  - (a) A platform no-op acknowledger that marks them DELIVERED. The history is lost for future consumers.
  - (b) Stop emitting the type until a consumer exists. This changes a published contract.
  - (c) A new terminal status, e.g. `SKIPPED`, with a migration and CHECK change.
  - (d) Keep the current state: they are excluded from scheduling and alerts, and cost index space.
- *Safe work that continues:* all of the above is done; they are reported as `unrouted`.

**B2 — HTTP exposure of global metrics**
- *Why it blocks:* who may read cross-tenant aggregates is a security-policy decision.
- *Options:* an operator-only actuator endpoint, a separate management port, or a push registry.
- *Current state:* meters are registered and the log-based alert contract is active.

**Deferred**
- Partial-grant semantics for cycle-benefit bundles (product decision).
- Bounded per-tenant parallel dispatch (commit-bound per the §9 profile). It needs pool-sharing analysis with HTTP threads (Hikari 8).
- Transient vs. permanent failure budgets.
- Fair scheduling for the other lanes.
- Retention/archival for DELIVERED events and inbox rows.

## 13. Change set classification

- **PRODUCT_CODE (this phase):**
  - `platform-runtime/.../EventDispatcher.java` (also carries R1 changes)
  - `EventHealthLog.java` (new)
  - `JsonCodec.java`
  - `persistence/EventMapper.java`
  - `mappers/runtime/EventMapper.xml`
  - `trade/.../QuoteService.java`
  - `commerce-app/.../PaymentController.java`
  - `EventRuntimeMetrics.java` (new)
  - `V35__event_scheduling_index_and_failure_evidence.sql` (new)
- **TEST_CODE:**
  - `EventSchedulingFairnessTest`, `EventSchedulingBenchmarkTest` (opt-in), `EventHealthAlertTest`, `EventRuntimeObservabilityTest`, `LegacyQuoteSnapshotTest` (all new)
  - `AuthorizationCoverageTest` (R1 file, one test added)
- **DOCUMENTATION:**
  - `docs/design/unified-commerce/BACKEND_ARCHITECTURE.md` and `docs/doc-map.md` (both also carry R1 edits)
  - this report
- **GENERATED_ARTIFACT:**
  - `docs/evidence/phase2-event-runtime/*` (jsonl, logs, `browser-results.json`, screenshots, ~4 MB)
  - `target/` and `frontend/dist` (ignored)
- **LOCAL_RUNTIME_ARTIFACT (ignored):** `.local/e2e-access.json`, `.local/member-suite-access.json`, `.local/operations-access.json`, `.local/browser-results/`
- **PRE_EXISTING, R1 (uncommitted):**
  - `EventWorker`, `SecurityConfiguration`, `PersistedCommerceTest`, `ModuleBoundaryTest`
  - `SegmentService`, `MemberCycleService`, `MemberPointsService`
  - `OrderApi`, `OrderService`, `OrderMapper` (+xml), `PaymentMapper` (+xml), `PaymentClosingHandler`
  - `EventConsumerIsolationTest`, `EventWorkerLaneTest`, `WorkerResilienceTest`
  - `docs/evidence/remediation-r1/`, `.project-analysis/`
- **PRE_EXISTING_USER_CHANGE (untouched):** `CODEX_PROGRESS.md`, `frontend/src/**`, `frontend/vite.preview-8602.config.ts`, `scripts/seed-shop-catalog.py`, `.engineering/exploration/`

Nothing was committed, staged, reverted or cleaned.

## 14. Commit boundary recommendation

R1 is still uncommitted, and `EventDispatcher.java`, `AuthorizationCoverageTest.java`, `BACKEND_ARCHITECTURE.md` and `doc-map.md` contain both R1 and Phase 2 edits. They cannot be split without reconstructing the intermediate version.

1. `fix(runtime): R1 worker/consumer isolation, expiry, authz, lock order`: the R1-only files in §13, **excluding** `EventDispatcher.java` and `EventConsumerIsolationTest`.
2. `feat(events): tenant-fair budgeted dispatch, due index, failure evidence`:
   - `EventDispatcher.java`, `EventMapper` (java+xml), `V35`
   - `EventConsumerIsolationTest`, `EventSchedulingFairnessTest`, `EventSchedulingBenchmarkTest`
3. `fix(trade): decode pre-funding coupon quote snapshots`: `JsonCodec`, `QuoteService`, `LegacyQuoteSnapshotTest`.
4. `feat(events): backlog diagnostics, metrics and alert contract`:
   - `EventHealthLog`, `EventRuntimeMetrics`, `PaymentController`
   - `EventHealthAlertTest`, `EventRuntimeObservabilityTest`
   - `AuthorizationCoverageTest`
5. `docs: event scheduling contract, 403/404 contract, phase 2 evidence`: the docs plus text evidence. Consider leaving the screenshots out.

## 15. Next recommended phase

1. Decide B1 and B2.
2. Apply the budgeted fair rotation to the order-expiry and payment/refund lanes, measured the same way.
3. Transient-aware retry budgets.
4. Measured bounded parallel dispatch across tenants.
5. Event/inbox retention.
