# Phase 3 — Background Runtime Convergence, Failure Semantics & Operational Hardening

- **Phase:** 3
- **Final Status: PHASE_3_COMPLETE_WITH_LIMITATIONS**
- Date: 2026-09-26. Starting point: `main` @ `86d7e0a` + the uncommitted R1 and Phase 2 working tree.
- Environment: Java 21, MySQL 8.4 (Docker, local), isolated schema `commerce_test_20260923`. `commerce_local`, the frontend sources and the system proxy were not modified. Nothing was committed, staged, reset, stashed or cleaned.

## Executive Summary

The event dispatcher's fairness model is now shared by every background lane, and the scheduler topology no longer lets one lane delay the others.
- **Topology.** Each of the 10 lanes is an independent fixed-delay task on a bounded 3-thread pool, instead of 10 lanes in sequence on one thread. With a 2 s slow payment channel, unrelated lanes kept a ≤ 10 ms start lag (previously 1.5–2 s).
- **Shared rotation.** `TenantRotation` is one primitive used by 10 lanes. It replaces the "4 tenants per tick" loops and the global-FIFO member lanes. In the order-expiry benchmark, all 300 small tenants were served in 5 ticks instead of 76, and the drain throughput rose from 7.9 to 35.7 orders/s (modeled).
- **Failure semantics.**
  - `FailureClass` classifies by structure (never by message).
  - Transient failures no longer consume poison budgets.
  - A lane breaker stops "burning down" healthy work.
  - In a live 80 s DB cut with 300 due events, 0 events were charged or quarantined, and all were delivered after recovery.
- **B1.** Declared no-consumer events are written as `SKIPPED` (`order.fulfilling.v1`). Undeclared no-consumer events raise `EVENT_NO_REQUIRED_CONSUMER`.
- **B2.** Cross-tenant health is available only to a new `PLATFORM_OPERATOR` role with the capability `EVENT_RUNTIME_METRICS_READ`, checked at two layers.
- **Bugs found and fixed:**
  - A tenant-level poison bug in R1 order expiry: one bad order rolled back a 20-order batch forever.
  - History-scanning queries in 3 lanes (16–70 ms → < 1 ms).
  - A breaker gap in the event lane.
  - Recovery latency after long outages (breaker cap lowered to 1 min).
  - A defect in my own mutation harness. It was found through live testing, corrected, and everything affected was re-run.

## Starting Baseline

`01-baseline.md`: 244 run / 243 passed / 0 failed / 1 skipped, matching Phase 2.

## Background Runtime Inventory

`02-background-lane-inventory.md`:
- There were 10 lanes and one scheduler thread. No other recurring work existed: no executors, cleanup jobs or listeners.
- Critical findings:
  - Order expiry was all-or-nothing per 20-order tenant batch.
  - Points and cycles used a global LIMIT 20 FIFO with no attempt state.
  - Payment/refund checks were exhausted by transient failures in ~2 min.
  - The item lanes (segments, journeys, deliveries, catalog-jobs) also isolate after 5 failures, within ~1 min.

## B1 Decision — No Consumer Events

`03-b1-no-consumer-events.md`:
- The audit covered all 16 emitted types. The only one without a consumer is `order.fulfilling.v1`, classified **INTENTIONALLY_UNHANDLED**: there is no relay, no contract requiring a consumer, and the state is authoritative in `order_record`.
- Terminal `SKIPPED` + `skip_reason`, set at write time from a publisher declaration (`UnconsumedEventType`).
- The application fails at startup if a declared type also has a consumer.
- Undeclared types without a consumer stay PENDING and raise an alert (REQUIRED_CONSUMER_MISSING). They are never auto-skipped.
- V36 converted 413 test rows and 8 local rows. Rows are retained and can be replayed by an admin once a consumer exists.

## B2 Decision — Cross-Tenant Metrics Authorization

`04-b2-cross-tenant-metrics.md`:
- `PLATFORM_OPERATOR` has no tenant permissions.
- `GET /v1/platform/runtime` is guarded by the route role **and** the use-case capability.
- Tested for anonymous 401, member/operator/tenant-admin 403, platform 200, and platform 404 inside its namespace.
- The platform role gets 403 on every tenant endpoint. Tenant health ignores `?tenant=`. The global view contains no identifiers.
- Metrics carry no tenant labels (only a fixed `lane` tag).

## Architecture Changes

`05-architecture-and-scheduling-model.md`. New in `platform-runtime`, the narrowest layer shared by all modules:
- `TenantRotation`, `FailureClass`, `RetryPolicy`, `WorkLanes`, `UnconsumedEventType`.

New in the app shell:
- `LaneMonitor`, `BackgroundRuntime`, `BackgroundLaneMetrics`, `PlatformRuntimeController`, and `EventWorker` as the topology.

All 10 lanes were migrated. Migrations:
- V36: failure-semantics columns, SKIPPED status, platform role, order-expiry retry state, check evidence.
- V37: four tenant-lane indexes.

Schema changes are additive (columns, constraints extended, one VARCHAR widened, indexes). V36 also moves the existing `order.fulfilling.v1` PENDING rows to SKIPPED. `ModuleBoundaryTest` passes, and no scheduler logic lives in controllers.

## Scheduling Model

- Lanes are fixed-delay (1 s) tasks on K=3 threads, taken in trigger-time order.
- Worst lane start lag ≈ ⌈(L−1)/K⌉ × the longest budgeted run.
- Each thread holds at most one DB connection, which leaves 5 of the 8 pool connections for HTTP.

## Tenant Fairness

- Every lane has an explicit contract: quantum, budget, fresh/backlog treatment and hot-tenant behaviour. See the table in 05 §3; values are per lane, not copied from the event lane.
- A tenant with due work is served within one rotation. The rotation length is observable, and `LANE_ROTATION_SLOW` fires above 5 min.
- A hot tenant gets ≤ 1 quantum before every other tenant with due work is served. This is tested for orders, points, the rotation primitive and events.

## Cross-Lane Fairness

`08-performance-and-load.md` §3 (40 s window):

| Case | Payment P50 | Expiry backlog | Worst unrelated-lane lag |
|---|---|---|---|
| 1 thread | 1,595 ms | not drained | 1,013 ms |
| 3 threads | **321 ms** | 38 s | 8 ms |
| 1 thread + 2 s slow channel | 1,796 ms | not drained | **2,042 ms** |
| 3 threads + 2 s slow channel | 1,280 ms | 37 s | **10 ms** |

Retries no longer compete with fresh events: the fresh lane only takes never-failed events (tested with a 300-retry storm).

## Failure Classification

`06-retry-taxonomy-and-failure-matrix.md` §1:
- 8 classes, derived from Spring DAO/transaction types, JDBC subclasses, SQLState class (08/40/42), `DomainException` codes and Jackson exceptions.
- Messages are never read.

## Retry Model

- POISON: 2/4/8/16 s, quarantine at 5.
- TRANSIENT: 2 s doubling to 5 min, +20% jitter, 300 failures (≈ 27 h).
- Lanes whose items have a business deadline use a ≈ 32 s deferral without counting, and the deadline ends the retries.
- Payment/refund transient failures refund the claimed check.
- Lane breaker: after 3 consecutive transient failures, a 5 s → 1 min cool-down; the first success closes it.
- Jitter is injectable, so the tests are deterministic.

## Quarantine Model

- Quarantine is reached only after 5 non-transient failures or about 27 h of transient failure.
- Evidence kept (no payload): class, consumer:type, both counters, first and last failure time, manual retries.
- Operator retry for events (ISOLATED or SKIPPED) and for stopped order expiry:
  - it is audited and idempotent;
  - it keeps the evidence;
  - it runs only unfinished consumers.

## Concurrency / Claiming Model

`05` §5:
- Events: per-consumer tx, `SKIP LOCKED`, inbox.
- Orders: per-order tx, `SKIP LOCKED` claim, locked re-check. 4 instances × 400 orders produced exactly once.
- Payments/refunds: a CAS claim committed before the call.

Parallel experiment (P3.6):
- 1/2/4 workers gave 124/188/337 ev/s with 0 duplicates and 0 row-lock waits. Connections grow linearly with workers, and the worst tenant wait does not improve beyond 2.
- **Decision: not enabled.** Horizontal instances are proven safe and are the recommended scaling lever.

## Database / Index Results

`07-database-query-evidence.md`:
- With a skewed tenant (60k history rows), the optimizer's PRIMARY-key choice scanned the whole history.
  - Order expiry: 16.2 → 0.11 ms per visit, 15.8 → 0.21 ms per discovery.
  - Payments: 17 → 0.05 ms and 18.6 → 0.87 ms.
  - Points: 69.5 → 0.05 ms and 15.7 → 0.81 ms.
- Fix: V37 status-first indexes, plus `FORCE INDEX` on discovery (a documented exception).
- The cycles discovery shape was measured and left as a limitation.

## Observability

- Metrics: `commerce.lanes.*{lane}` (items, completed, transient/other failures, breaker trips/open, rotation, backlog due/oldest/quarantined, start lag, run duration), plus new `commerce.events.*` meters (transient failures, transient retrying, skipped, breaker).
- Per-minute alert codes:
  - `LANE_STARVATION`, `LANE_DEPENDENCY_UNAVAILABLE`, `LANE_BACKLOG_AGE`, `LANE_QUARANTINE_GROWTH`, `LANE_ROTATION_SLOW`;
  - `EVENT_NO_REQUIRED_CONSUMER`, `EVENT_DEPENDENCY_UNAVAILABLE`;
  - the Phase 2 event codes, unchanged.
- Each code has a definition, trigger, clear condition, severity and operator action (`13-runbook.md`).

## Security Contract

- See the B2 section.
- Phase 2's 401/403/404 concealment contract is unchanged and re-tested.
- Admin retry is tenant-scoped, and tenant health remains own-tenant only.

## Performance Results

`08`. Local measurements only; not production capacity.
- Event lane (Phase 2 harness, 10k aged backlog): single/balanced/dominant drain 94–108 s modeled (Phase 2: 130–148 s). The victim is still served on the next tick (≈ 2.0 s).
- **Many-small** took 50 ticks versus 44 (reproduced twice). This is about 12% slower and the cause is not established; fairness is equal.

## Load / Backlog Results

- Scenarios A–H are covered (08 §5). The fixed old-vs-new expiry benchmark is in 08 §4.
- Live DB outage (`09`): 300/300 delivered, 48 s after restore (bounded by the in-flight cool-down); 0 charged, 0 quarantined.

## Tests

- **BUILD SUCCESS: 282 run, 278 passed, 0 failed, 4 skipped** (all opt-in benchmarks). Baseline: 243 passed.
- 38 new tests. See `11-regression.md`.

## Mutation / Negative Tests

`10-mutation-tests.md`:
- **11/11 must-detect mutations were detected** in the clean re-run.
- Removing `SKIP LOCKED` (events and expiry) survives as predicted. Correctness comes from the inbox and the locked re-check; `SKIP LOCKED` is a throughput property.
- **Harness defect.** Restored files kept their old mtime, so mutated classes leaked into later runs and into the first packaged jar. It was found when the live breaker did not trip, then fixed. All mutations, the final verify, the E2E run and the outage test were re-run on clean builds. Run 1 is kept as `mutation-results-run1-invalid.json`.

## Regression Results

- The regression matrix is all green (`11` §2).
- Browser E2E: **17/21**, the identical 4 failures from Phase 2 (existing frontend/spec mismatches in user-owned uncommitted UI). **0 new, 0 resolved.**

## Known Limitations

1. **Cycles discovery** joins all members of tenants that have a cycle policy (14.8 ms on test data, growing with members). Fixing it needs a due-date column/index on the cycle account model.
2. **Points and cycles have no per-item retry state.** Up to one quantum of persistently failing items can block that tenant's lane (other tenants are unaffected).
3. **Item lanes** (segments, journeys, deliveries, catalog-jobs):
   - Transient deferral has no counter; the business deadline bounds it.
   - Segment-start poison is mitigated (3 candidates per visit) but not eliminated.
   - The journey deadline-poison path is UNVERIFIED (inventory finding).
4. `DomainException(UNAVAILABLE)` is also used for "channel not configured". A misconfiguration is treated as an outage: long transient budget, breaker, `*_DEPENDENCY_UNAVAILABLE` alert.
5. Order/payment/refund failure evidence keeps only the last failure; there is no first-failure time.
6. After a dependency recovers, lanes resume within ≤ 60 s (the remaining breaker cool-down); measured 48 s.
7. Cursors, breakers and lane statistics are per instance. Multiple instances duplicate discovery; correctness is proven.
8. Intra-lane parallel dispatch is not enabled (decision, see P3.6).
9. The event many-small scenario is about 12% slower than Phase 2; the cause is not established.
10. There is no external alert delivery, and a fully stopped scheduler emits no log (probe `/v1/platform/runtime`).
11. Platform credentials are provisioned manually (SQL insert of the token hash). There is no API, by design.
12. The deadlock counter is unavailable to the app account (no PROCESS privilege); indirect evidence only.
13. Retention is not implemented (decision needed).
14. The test schema keeps accumulating residue (fixture tenants), as in Phase 2.
15. The raw evidence files (`*.log`, `*.json`, `*.jsonl`, `*.txt` under `docs/`) are ignored by a `.gitignore` change made outside this session at 12:45. Phase 3 left `.gitignore` untouched. The markdown files quote every number the report relies on.

## Blocked / Deferred Items

| Item | Type | Owner |
|---|---|---|
| Retention horizon for DELIVERED/SKIPPED events, commands and audit (proposal: 30/90 days, never ISOLATED/PENDING) | product/legal decision | product, legal (`12-retention-proposal.md`) |
| Partial benefit-bundle grants (N3, carried from Phase 2) | product decision | product |
| External alert provider routing for the fixed codes | integration decision | operations |
| Management port / network boundary for platform endpoints | deployment topology | operations |

None of these blocks the Phase 3 guarantees.

## Changed Files (Phase 3)

Several files also carry R1 and/or Phase 2 edits (marked †).

- **platform-runtime**
  - new: `FailureClass`, `RetryPolicy`, `TenantRotation`, `WorkLanes`, `api/UnconsumedEventType`
  - modified: `EventDispatcher`†, `EventHealthLog`†(new in P2), `Outbox`, `api/Actor`, `api/EventHandler`, `persistence/EventMapper`†, `mappers/runtime/EventMapper.xml`†
- **order-runtime**
  - `OrderApi`†, `OrderService`†, `OrderMapper`† (java/xml)
  - new: `OrderEventContracts`
- **payment:** `PaymentService`, `RefundService`, `PaymentMapper`† (java/xml), `RefundMapper` (java/xml)
- **member:** `MemberCycleService`†, `MemberPointsService`†, `CycleMapper` (java/xml), `PointsMapper` (java/xml)
- **marketing-runtime:** `SegmentService`†, `SegmentMapper` (java/xml)
- **marketing-automation:** `CouponDeliveryService`, `JourneyService`, `CouponDeliveryMapper` (java/xml), `JourneyMapper` (java/xml)
- **catalog:** `CatalogJobService`, `CatalogJobMapper` (java/xml)
- **commerce-app**
  - modified: `EventWorker`†, `SecurityConfiguration`†, `PaymentController`†, `EventRuntimeMetrics`†(new in P2), `application.yml`
  - new: `LaneMonitor`, `BackgroundRuntime`, `BackgroundLaneMetrics`, `PlatformRuntimeController`, `V36__background_runtime_failure_semantics.sql`, `V37__tenant_lane_indexes.sql`
- **tests**
  - new: `TenantRotationTest`, `FailureSemanticsTest`, `EventFailureSemanticsTest`, `OrderExpiryLaneTest`, `PaymentCheckLaneTest`, `MemberPointsLaneTest`, `PlatformRuntimeAuthorizationTest`, `BackgroundRuntimeBenchmarkTest` (opt-in)
  - modified: `EventWorkerLaneTest`†, `EventHealthAlertTest`†, `EventRuntimeObservabilityTest`†, `AuthorizationCoverageTest`†
- **docs:** `docs/design/unified-commerce/BACKEND_ARCHITECTURE.md`†, `docs/doc-map.md`†, `docs/evidence/phase3-background-runtime/**` (13 documents + scripts + raw evidence)
- **Untouched user work:** `CODEX_PROGRESS.md`, `frontend/src/**`, `frontend/vite.preview-8602.config.ts`, `scripts/seed-shop-catalog.py`, `.engineering/exploration/`, `.gitignore`

## Evidence Location

`docs/evidence/phase3-background-runtime/`:
- documents 01–13 and this report;
- `scripts/`: mutation harness, query-plan script and datasets, DB proxy;
- raw evidence: `verify-final.log`, `baseline-verify.log`, `query-plans.txt`, `*.jsonl` benchmarks, `mutation-results*.json`, `runtime-*.txt/json`, `e2e/`.

## Commit Boundary Recommendation

R1 and Phase 2 are still uncommitted, and 20 files now carry edits from two or three phases (†). A per-phase history therefore needs hunk-level staging (`git add -p`) by the owner. If that is not worth it, commit in this order. Each step builds and passes on its own because the later ones only add.

1. `fix(runtime): R1 worker/consumer isolation, expiry, authz, lock order`: the R1-only files listed in the Phase 2 report §13.
2. `feat(events): Phase 2 fair dispatch, due index, failure evidence, diagnostics, legacy quote decode`: the Phase 2 files (V35, `EventHealthLog`, `EventRuntimeMetrics`, `JsonCodec`, `QuoteService` and the Phase 2 tests).
3. `feat(runtime): shared tenant rotation, failure taxonomy and classified retries for all lanes`:
   - `platform-runtime` primitives, the event changes (B1 `SKIPPED`, retry fairness, breaker);
   - all lane migrations; V36 and V37;
   - `TenantRotationTest`, `FailureSemanticsTest`, `EventFailureSemanticsTest`, `OrderExpiryLaneTest`, `PaymentCheckLaneTest`, `MemberPointsLaneTest`.
4. `feat(ops): per-lane scheduler topology, platform runtime view (B2), lane alerts and metrics`:
   - `EventWorker`, `LaneMonitor`, `BackgroundRuntime`, `BackgroundLaneMetrics`, `PlatformRuntimeController`, `SecurityConfiguration`, `Actor`, `application.yml`;
   - the lane/authorization/observability tests.
5. `test(bench): opt-in background runtime benchmarks`: `BackgroundRuntimeBenchmarkTest`.
6. `docs: phase 3 background runtime contract, runbook and evidence`. Markdown only, given the current `.gitignore`.

## Next Recommended Phase

1. Model cycle due dates (an indexed `next_assessment_at`), and add per-item retry state for points and cycles to close limitations 1–2.
2. Implement retention once the product/legal horizon is decided, as an 11th budgeted lane.
3. Wire the fixed alert codes to an external alert provider, plus an external liveness probe of `/v1/platform/runtime`.
4. If horizontal scaling is planned, coordinate instances: tenant-range partitioning or shared cursors, to remove duplicated discovery.
5. Realign the 4 E2E specs with the current UI (R1 finding N5), owned by the frontend work.
