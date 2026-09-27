# Phase 4 — Runtime Recovery, Data Lifecycle, Replay & Production Operations

- **Phase:** 4
- **Final Status: PHASE_4_COMPLETE_WITH_LIMITATIONS**
- Date: 2026-09-26. Start: `main` @ `064d44b` (clean tree).
- Environment: Java 21, MySQL 8.4 (Docker, local), isolated schema `commerce_test_20260923`.
- Nothing was committed, pushed, reset, stashed or cleaned. `commerce_local`, the frontend sources and the system proxy settings were not modified.

## Executive Summary

The background runtime can now be **recovered, replayed, lifecycle-governed and operated**, and every claim is backed by a test that removes the protection and fails.

- **Item-level retry (L1, P0).**
  - Points expiry and cycle assessment now keep per-item retry state (`member_work_retry`: classified budgets, backoff, quarantine, evidence).
  - A tenant with more failing items than one visit quantum is no longer blocked. Its healthy items finish within a few ticks; before, they never did.
- **Cycles scalability (L4).**
  - The due set is now an indexed column (`member_record.cycle_due_at`), with policy changes pulled forward in 500-member batches.
  - With a 100k-member tenant, discovery went from 182 ms to 4.7 ms and the visit from 161 ms to 1.6 ms. Rows read dropped from 100,001 to 1.
  - A query-plan gate test prevents a regression.
- **Governed recovery (L2).**
  - One recovery API covers 4 work types: inspect → classify → explicit ids (≤ 50) → execute → audit.
  - It uses tenant-scoped capabilities, idempotent commands, a failure-class guard, and a per-item audit (`platform_recovery`, including rejections).
  - All legacy retry and cancel controls now write the same audit.
- **Replay safety (L2).**
  - Every event consumer declares its side-effect class. A fail-closed gate rejects financial, external and irreversible effects even when they are declared replayable.
  - Only the pure projection `marketing-effects-v1` can be replayed. Every other consumer is **REPLAY_NOT_SUPPORTED**, backed by a source-level evidence matrix.
  - Replay jobs are bounded, dry-runnable, pausable, cancelable, audited, and yield to live work. Two instances never execute an event twice.
- **Retention (L3).**
  - Complete, tested, fail-safe, bounded mechanism: terminal events and their inbox rows are deleted in one transaction; unfinished work, dedup, replay ranges and audit are never touched.
  - Every duration is configurable with floors, and retention is **disabled by default** because the retention horizon is an open product/legal decision.
- **Crash / restart / multi-instance (L5).**
  - C1–C6 were proven with a transaction-level crash injector and a live `kill -9`.
  - Concurrency tests found **two real defects** in the new recovery code (a RESOLVED double-apply and a stale audit state); both were fixed.
- **Operations (L6).**
  - SLO/SLI table, 3 new alert codes, a provider-independent `OperationalAlertPublisher`, a runbook covering the five backlog situations, breaker, quarantine, replay and retention, and a measured capacity model.
- **Performance (L7).**
  - The ~10% many-small gap reproduces. The profile shows 95% of time in the unchanged per-event transaction, ≤ 5% in per-tenant switching (fairness), and 0.014% in rotation code.
  - Classified **KNOWN_PERFORMANCE_TRADEOFF**. Fairness was not weakened.

## Starting Baseline

`00-baseline.md`:
- backend 282 run / 278 passed / 0 failed / 4 skipped (identical to Phase 3);
- `ModuleBoundaryTest` green;
- browser 17/21 with the same 4 known failures;
- clean tree.

## Phase 3 Limitations Addressed

| Limitation | Result |
|---|---|
| L1 points/cycles item retry (P0) | **Fixed** (02) |
| L2 recovery / replay semantics (P0) | **Fixed**: recovery (04), replay (05, 06) |
| L3 retention semantics | **Mechanism implemented**; the horizon is a **deferred product/legal decision**; disabled by default (07, 08) |
| L4 cycles discovery growth | **Fixed** and gated (03) |
| L5 crash/restart certification | **Proven** (09, 10, live `kill -9`) |
| L6 runbooks / SLOs | **Done** (12, 13, 16) |
| L7 many-small ~12% | **Investigated**: KNOWN_PERFORMANCE_TRADEOFF (14) |
| Phase 3 limitation 5 (order evidence has no first-failure time) | unchanged, documented (order recovery lists `firstFailedAt=null`) |

## Runtime State Model

`01-runtime-state-model.md`:
- Scheduling ≠ Retry ≠ Recovery ≠ Replay ≠ Retention, each with separate state.
- The existing states are reused and mapped onto the brief's illustrative states. Only `member_work_retry`, `OPERATOR_SKIPPED`, `platform_recovery` and `platform_replay` were added.
- There is no persisted RECOVERING state: recovery is one synchronous conditional update inside the command transaction.

## Item-Level Retry Isolation

`02`:
- `member_work_retry` exists only while an item fails and is deleted in the success transaction.
- Counting is atomic (`INSERT … ON DUPLICATE KEY UPDATE`).
- The due queries exclude items in backoff or quarantine by primary key.
- Tests: 8, covering all four scenarios in the brief plus the P0 "more bad items than one quantum" case, for both lanes.

## Cycles Scalability

`03`:
- **Cause:** the data-model access pattern (an anti-join made "due" unindexable).
- **Fix:** an explicit due time, batched policy rollout, and an O(1)-per-tenant discovery probe. The probe needs `ORDER BY tenant_id, cycle_due_at`; two intermediate variants measured 33–39 ms and are kept as evidence.
- **Gate:** `EXPLAIN FORMAT=JSON` on the bound mapper SQL checks index, no base-table `ALL`, and no member filesort.

## Recovery Model

`04`:
- `RecoverableWork` SPI with 4 registered types (event, order.expiry, member.points.expiry, member.cycle.assessment), `RuntimeRecovery`, `RecoveryAudit`.
- RETRY keeps all failure evidence; SKIP exists for events only. The rejection codes are `NOT_STOPPED`, `FAILURE_CLASS_MISMATCH`, `STATE_CHANGED` and `NOT_FOUND`.

## Quarantine Recovery

- Explicit ids only (≤ 50), a required reason, and an optional class guard. **There is no "retry all".**
- Scoped to the tenant (from the credential), the work type, the failure class and the ids.
- The per-item audit answers who, when, what, previous → new state, reason and result.
- R5 holds: nothing automatic clears a quarantine.

## Replay Semantics

- Retry = automatic continuation. Recovery = an operator moving stopped work. Replay = deliberate re-execution of **DELIVERED** events for **one consumer**.
- Modes: `UNPROCESSED` (the inbox is the dedup boundary) and `REPROCESS` (pure projections only).
- Scope: own tenant, ≤ 31 days, ≤ 10,000 events, ≤ 3 active jobs.

## Replay Safety Matrix

`05` (condensed):

| Consumer | Side effect | Protection | Replay safe | Automatic replay | Manual recovery |
|---|---|---|---|---|---|
| marketing-effects-v1 | PURE projection | PK + monotonic upsert, re-reads authoritative data | **YES** | NO (explicit job) | RETRY/SKIP |
| order-payment-v1 | FINANCIAL | payload = terminal payment row, order CAS | NO | NO | RETRY |
| payment-order-closing-v1 | EXTERNAL + FINANCIAL | status guard | NO | NO | RETRY |
| member-growth-v1 | points/growth/level (COMPENSATABLE) | source keys, delta | NO | NO | RETRY |
| benefit-refund-v1 | benefit revoke / coupon restore | status CAS | NO | NO | RETRY |
| aftersale-refund-v1 | points return | case state + PK | NO | NO | RETRY |
| internal-entitlement-grant-v1 | benefit grant | REQUESTED + version | NO | NO | RETRY |
| member-cycle-benefit-v1 | level benefit | source unique key | NO | NO | RETRY |
| journey-order-paid-v1 | enrolment → coupons/grants | entry unique key, time window | NO | NO | RETRY |
| fulfillment-order-v1 | shipping queue | PK upsert | NO | NO | RETRY |

## Sensitive Side-Effect Protection

`05` §4.1 lists, per workload, the idempotency mechanism, dedup boundary, business key, DB constraint, provider protection and replay permission, covering payments, refunds, points, benefits, coupons, inventory, fulfillment and notifications.

- Tests assert real side effects, not only statuses:
  - projection rows;
  - ledger `EXPIRE` rows (exactly one per lot under concurrency and after a live `kill -9`);
  - `SUM(expired)`;
  - inbox rows;
  - consumer effect rows under crash injection.

## Crash / Restart Recovery

`09`:
- C1 (before transaction), C2 (during DB work), C3 (after the business change, before progress), C4 (between consumers), C5 (during the retry-state update), C6 (restart with a backlog, breaker, quarantine) all pass.
- Live `kill -9` result: see 16 §1.
- No correctness state lives in process memory. Cursors, breakers and statistics reset safely.

## Multi-Instance Recovery

`10`:
- No duplicate consumer success (replay across 2 instances).
- No duplicate points side effect (lane + 3 concurrent admin commands).
- No lost recovery request (8 concurrent → 1 applied, 8 audited).
- No corrupt counters (2 × 50 → 100).
- Only valid transitions (SKIP/RETRY races).
- **No leases added** (§20): transaction-scoped locks proved sufficient once the two defects were fixed with row locks.

## Retention Decision

`07`:
- Matrix for DELIVERED, SKIPPED, ISOLATED and PENDING events, inbox, commands, audit, recovery audit, replay jobs, retry state and diagnostic evidence.
- **DELETE only, no archive** (no evidence that an archive is needed).
- Durations are **not** hard-coded: 7-day and 30-day floors, default disabled.
- The 30/90-day values are recorded as proposals to the owner.

## Retention Execution

`08`:
- A 12th lane every 5 s: 500 rows per batch, ≤ 2,000 rows or 300 ms per run, `READ COMMITTED`, `SKIP LOCKED`.
- New indexes `ix_inbox_event` and `ix_command_created`.
- Yields to live work. A count mismatch rolls the batch back.
- Metrics per class; `RETENTION_LAG_HIGH` and `RETENTION_FAILURE`.
- 7 tests. They use a 1991 clock so they cannot touch shared data.

## Operational SLO / SLI

`12` §1: 14 SLIs, each with its metric source, a warning threshold (the evaluated code) and a critical escalation rule. They are explicitly **not** customer SLAs.

## Alert Codes

- Phase 3 set, plus **REPLAY_BLOCKED**, **RETENTION_LAG_HIGH** and **RETENTION_FAILURE**, each with meaning, trigger, clear condition, severity and response (`12` §2).
- Rejected as unjustified by runtime state: `RECOVERY_FAILED`, `RETRY_STORM`.
- Codes are published through `OperationalAlertPublisher`. The default logs; no vendor is integrated (§31).

## Security / Authorization

`11`:
- New tenant-ADMIN capabilities: `RUNTIME_RECOVERY_READ`, `RUNTIME_RECOVERY_EXECUTE`, `RUNTIME_REPLAY_EXECUTE`. They are checked at two layers (route and use case).
- `PLATFORM_OPERATOR` has no mutation path. Retention has no HTTP administration.
- The anonymous 401 / member, store-operator and platform 403 / admin 200 / in-area 404 matrix is tested.
- Tenant boundary tested. The Phase 3 B2 tests are unchanged and green.

## Performance Investigation

`14`: reproduced (48–49 vs 44 ticks) and profiled.

| Share of wall time (many-small) | |
|---|---|
| per-event transaction (Phase 2 design) | ≈ 95% |
| per-tenant switch (fairness) | ≤ 5% |
| rotation code | 0.014% |

The new Phase 4 indexes on the delivery path have no measurable effect. **KNOWN_PERFORMANCE_TRADEOFF**, no code change.

## Capacity / Soak Results

`16-capacity-soak.md`.

**Live `kill -9` mid-backlog:**
- killed at 213 / 2,000 lots expired and 184 / 500 members assessed;
- after restart: 2,000 lots expired with **exactly one `EXPIRE` ledger row each**, `SUM(expired)=20,000`, 500 assessments.

**600 s soak (all at the same time):**
- 568 real orders (all delivered once, 0 duplicate inbox rows, 0 ISOLATED);
- a 30 s DB outage (8 breaker trips, cleared, 0 items charged);
- 5 poison lots (quarantined → recovered → re-quarantined, all audited);
- a replay of 210 events (COMPLETED);
- retention purging 3,000 old events and their inbox rows;
- heap: a stable sawtooth; threads 45–47; DB connections 4–5 of 8; normal lane start lag ≤ 26 ms.

Capacity model: 12 lanes on 3 threads; event lane ≈ 200 events/s processing per instance; retention ≤ 400 rows/s; replay ≤ 100 events/s. **Additional instances are the scaling lever.** These are local measurements, not production guarantees.

## Tests

- **Final clean build:** `scripts/build.sh` (`npm ci`, UI build, `mvn -Pwith-ui clean verify`, jar check). **BUILD SUCCESS: 329 run / 324 passed / 0 failed / 5 skipped** (4 opt-in benchmarks and the opt-in profiler). Baseline was 282 / 278.
- 46 new tests in 8 classes, plus 1 opt-in profiler.
- 2 Phase 3 tests adjusted: the lane set, and cleanup.

## Mutation / Negative Tests

`15` §3: **18/18 must-detect mutations detected.**
- 2 predicted survivors survive because of a second guard.
- 1 predicted survivor was detected (the stale-audit race).
- **Correction:** the first result for #6 (cycles probe) was invalid. The gate's new filesort check also failed on correct SQL, which the final build exposed. The check was fixed and re-validated in both directions (`mutation-rerun/`).
- One harness hygiene finding (backup files copied into `target/classes`) was fixed. The final jar was checked for it.

## Regression Results

`15` §2: every row of the §54 regression matrix passes in the final build, including:
- the Phase 3 fairness, isolation, failure-semantics, B1/B2 and 401/403/404 suites;
- `ModuleBoundaryTest`.

Artifact hygiene (`15` §5):
- clean build after every mutated file was restored and hash-verified;
- no backup files anywhere;
- the packaged mapper XML carries the protected constructs;
- the UI is packaged.

## Browser Regression

`15` §4 (final clean jar, fresh fixtures per run):
- final-r2 and final-r3: **17/21**, with the same 4 existing failures as the baseline (failure messages byte-identical).
- The first final run had one additional failure: operations "旅程与效果" (journeys and effects), a 409 on `/aftersales`.
  - It did not reproduce in the next 2 runs.
  - It is caused by a race in the spec's pump helper against the live event lane.
  - It is classified **intermittent**, and a timing influence from the two new lanes is disclosed.
- New regressions reproduced: 0. Resolved: 0.

## Recovery Invariants (§53)

| # | Invariant | Evidence | Holds |
|---|---|---|---|
| R1 | one bad item cannot block unrelated healthy items | `ItemRetryIsolationTest` (P0 case with more bad items than one quantum; lots and members); mutations 1–2 | yes |
| R2 | one bad tenant cannot block other tenants | `permanentlyFailingTenant…`, `poisonCycleTenant…`, Phase 3 rotation tests | yes |
| R3 | one failed lane cannot block another | Phase 3 `EventWorkerLaneTest`; soak (lags ≤ 26 ms outside the outage; 12 lanes) | yes |
| R4 | transient ≠ permanent | `transientLockTimeout…`, `retryBudgets…`, `CrashRecoveryTest.c6`, soak outage (0 charged); mutation 3 | yes |
| R5 | quarantine never silently returns | `badLotBacksOff…` (quarantined, no longer selected); only `RuntimeRecovery` clears `quarantined_at` | yes |
| R6 | every operator recovery is authorized and auditable | `RuntimeRecoveryTest` (audit contents, legacy endpoints, rejections), `MultiInstance…` (8 of 8 audited); mutations 13–14 | yes |
| R7 | successful consumers are not re-executed during recovery | `CrashRecoveryTest.c4`, Phase 3 `businessRejection…RunsOnlyUnfinishedConsumers`, inbox PK | yes |
| R8 | sensitive replay requires proven idempotency or is rejected | `ReplayTest.safetyGate…`, `sensitiveConsumers…`, `executionTimeGate…`; the matrix in 05; mutations 7–9 | yes |
| R9 | a crash cannot lose durable work | C1–C6, live `kill -9` (2,000 / 2,000) | yes |
| R10 | a restart cannot duplicate completed work | C4, C6, live `kill -9` (0 duplicate `EXPIRE` rows) | yes |
| R11 | multiple instances preserve recovery and replay correctness | `MultiInstanceRecoveryTest` (4), `ReplayTest.twoInstances…`; mutation 12 | yes |
| R12 | retention cannot delete what unfinished work, dedup, replay or audit need | `RetentionTest.onlyProvablySafe…`, `activeReplayRanges…`, `lockedRows…`; mutations 17–19 | yes |
| R13 | historical replay cannot starve live work | separate lane with a budget; `replayYieldsToLiveWork`; mutation 11; soak (replay next to live orders, lags unchanged) | yes |
| R14 | retention cannot monopolize the DB | batches, time budget, `READ COMMITTED`, `SKIP LOCKED`, yield; `cleanupIsBoundedPerRun`, `cleanupYieldsToLiveWork`; soak (4–5 connections) | yes |
| R15 | failures are diagnosable from metrics, codes, logs and runbooks | 12 (SLIs, codes), 13 (runbook), `OperationalAlertTest`, soak alert trace (outage codes raised and cleared) | yes |

Success criteria (§58):
- 1–16 are met by the evidence above.
- 11 holds because the retention durations are configurable and off by default.
- 16: the user's work was untouched (`frontend/src`, `CODEX_PROGRESS.md` and `commerce_local` were not modified).
- 17: the artifact was built clean after hash-verified restores (`15` §5).

The status is *with limitations* only because of the deferred decisions and the documented non-critical limitations below.

## Known Limitations

1. The retention horizon is not decided, so retention is off and storage keeps growing until the owner enables it (visible through `commerce.retention.lag` and the oldest-row age in the platform view).
2. Only one consumer is replayable. All other historical-effect consumers are `REPLAY_NOT_SUPPORTED` by evidence. Their unfinished work goes through recovery.
3. A tenant whose earliest-due member is blocked (backoff or quarantine) is still discovered each tick by the cycles lane. The cost is O(its blocked members).
4. Rolling out a new cycle policy for a very large tenant takes about members / 500 lane items (≈ 200 visits for 1M members). An immediately effective policy can therefore reach the last members minutes late; a future-dated policy has no delay.
5. Order-expiry evidence still lacks a first-failure time (carried from Phase 3).
6. Recovery SKIP exists only for events. For points, cycles and orders, skipping has no safe business meaning (documented).
7. Replay and retention budgets are constants, and the live-yield thresholds are configurable. Replay runs on every instance, serialized by row locks, which means duplicated discovery but no duplicated work.
8. Phase 3 limitations 3, 4, 6, 7, 8, 10, 11, 12 and 14 are unchanged: item lanes use deadline deferral; UNAVAILABLE also means "not configured"; ≤ 60 s resume; per-instance cursors; no intra-lane parallelism; no external alert delivery; manual platform credentials; no deadlock counter; test schema residue.
9. The many-small fairness overhead is ≤ 5% (KNOWN_PERFORMANCE_TRADEOFF).
10. The browser suite still has the 4 pre-existing frontend/spec mismatches. `operations.spec.ts` "旅程与效果" (journeys and effects) is intermittent (1 of 3 runs): its `events()` helper can return while the background lane holds an event. The fix belongs in the spec: poll for the fulfillment state instead of stopping at the first 0-pump.

## Blocked / Deferred Decisions

| Item | Type | Owner | Effect on Phase 4 |
|---|---|---|---|
| Retention horizon for DELIVERED/SKIPPED events and commands (proposal 30 / 90 / ≥ 30 days) | product/legal | product, legal | mechanism complete; off by default |
| Legal retention of `platform_audit` / `platform_recovery` | legal | compliance | never purged |
| Export/archive before deletion | product/ops | product | not built (no evidence of need) |
| Partial benefit-bundle grants (N3) | product | product | untouched. `member-cycle-benefit-v1` and `internal-entitlement-grant-v1` are non-replayable; their failures are recovered via RETRY with an audit. |
| External alert provider | integration | operations | `OperationalAlertPublisher` ready |
| Financial replay semantics (e.g. re-driving payment closes) | financial | finance/product | `REPLAY_NOT_SUPPORTED`; manual reconcile stays the path |

None of these blocks the mandatory Phase 4 goals. They are why the status is *with limitations*.

## Changed Files

**New (main):**
- platform-runtime:
  - `RuntimeRecovery`, `RecoveryAudit`, `EventRecovery`, `EventReplay`, `ReplayGate`, `RetentionLane`;
  - `api/RecoverableWork`;
  - `persistence/{RecoveryMapper, ReplayMapper, RetentionMapper}` (java + xml).
- member: `ItemRetries`, `MemberRecoveryConfiguration`, `WorkRetryMapper` (java + xml).
- order-runtime: `OrderExpiryRecovery`.
- commerce-app:
  - `RuntimeRecoveryController`, `OperationalAlertPublisher`, `AlertConfiguration`;
  - migrations `V38__member_item_retry_and_cycle_schedule.sql`, `V39__runtime_recovery_audit_and_replay.sql`, `V40__retention_indexes.sql`.

**Modified (main):**
- platform-runtime:
  - `EventDispatcher`: audited legacy retry, `consumes`;
  - `api/Actor`: capabilities;
  - `api/EventHandler`: replay classification;
  - `EventMapper`: isolated, view, lockView, skip (java + xml).
- member:
  - `MemberPointsService`, `MemberCycleService`;
  - `PointsMapper`, `CycleMapper` (java + xml).
- order-runtime: `OrderService` (audited retry), `OrderMapper` (java + xml).
- Recovery audit in the module controls: `SegmentService`, `JourneyService`, `CouponDeliveryService`, `CatalogJobService`.
- Replay classification only: `PaymentService`, `PaymentClosingHandler`, `FulfillmentService`, `MarketingEffectsService`, `AftersaleService`, `EntitlementService`, `MemberBenefitService`, `MemberGrowthHandler`, `BenefitCompensationHandler`.
- commerce-app: `EventWorker` (12 lanes), `BackgroundRuntime` (replay/retention view, codes, publisher), `BackgroundLaneMetrics`, `application.yml` (documented properties).

**Tests:**
- new: `ItemRetryIsolationTest`, `CycleScheduleTest`, `RuntimeRecoveryTest`, `ReplayTest`, `RetentionTest`, `CrashRecoveryTest`, `MultiInstanceRecoveryTest`, `OperationalAlertTest`, `EventDispatchProfileTest` (opt-in), plus helpers `Phase4Properties` and `Phase4Http`;
- modified: `EventRuntimeObservabilityTest`, `WorkerResilienceTest`.

**Docs:**
- `docs/design/unified-commerce/BACKEND_ARCHITECTURE.md` (Phase 4 contracts), `docs/doc-map.md`;
- `docs/evidence/phase4-runtime-recovery/**`.

**Untouched user work:** `frontend/src/**`, `CODEX_PROGRESS.md`, `commerce_local`, `.engineering/`.

## Evidence Location

`docs/evidence/phase4-runtime-recovery/`:
- documents 00–16 and this report;
- `scripts/`: `cycles-query.sh`, `cycles-probe-variants.sh`, `browser-e2e.sh`, `mutate.py`, `live-soak.py`;
- raw files:
  - `cycles-query-*.txt`, `cycles-probe-variants.txt`;
  - `event-benchmark-*.jsonl`, `event-profile.jsonl`;
  - `mutation/`, `live/`, `e2e/`;
  - `baseline-verify.log`, `final-verify.log`, `retention-growth.txt`.

The raw `*.log`, `*.json`, `*.jsonl` and `*.txt` files under `docs/` are git-ignored by the repository `.gitignore`. The markdown documents quote every number this report relies on.

## Commit Boundary Recommendation

Each step compiles and passes on its own; later steps only add.

1. `feat(member): item-level retry state for points expiry and cycle assessment` — V38 (retry table only), `WorkRetryMapper`, `ItemRetries`, the points/cycles mapper and service changes that exclude blocked items, `ItemRetryIsolationTest`, and the `WorkerResilienceTest` cleanup.
2. `perf(member): indexed cycle due time and batched policy rollout` — the rest of V38 (`cycle_due_at`, rollout columns), the cycles discovery and visit queries, `CycleScheduleTest` (plan gate).
   - Because V38 is one file, steps 1 and 2 can also be one commit: `feat(member): item retry isolation and scalable cycle scheduling`.
3. `feat(runtime): governed recovery with audit` — V39 (`platform_recovery` part), `RecoverableWork`, `RuntimeRecovery`, `RecoveryAudit`, `EventRecovery`, `OrderExpiryRecovery`, member recovery beans, the `Actor` capabilities, audit in the legacy and module controls, `RuntimeRecoveryController` (recovery endpoints), `RuntimeRecoveryTest`, `MultiInstanceRecoveryTest`.
4. `feat(runtime): replay safety classification, gate and bounded replay` — V39 (`platform_replay` + index), `EventHandler.replaySafety` and the 10 declarations, `ReplayGate`, `EventReplay`, the replay endpoints and lane, `ReplayTest`, `CrashRecoveryTest`.
5. `feat(runtime): configurable fail-safe retention lane (disabled by default)` — V40, `RetentionLane`, `RetentionMapper`, `application.yml`, `RetentionTest`.
6. `feat(ops): alert publisher boundary, replay/retention metrics and codes` — `OperationalAlertPublisher`, `AlertConfiguration`, `BackgroundRuntime`, `BackgroundLaneMetrics`, `EventWorker`, `OperationalAlertTest`, `EventRuntimeObservabilityTest`.
7. `test(bench): opt-in dispatch profiler` — `EventDispatchProfileTest`.
8. `docs: phase 4 runtime recovery contracts, runbook and evidence`.

Because V39 carries both the recovery and replay tables, steps 3 and 4 can be combined, or V39 can be split before committing. It has only been applied to the local test schema.

## Next Recommended Phase

1. The owner decides the retention horizons. Then enable retention in a staging environment with `RETENTION_LAG_HIGH` watched.
2. Wire `OperationalAlertPublisher` to the chosen provider, and add an external liveness probe of `/v1/platform/runtime` (a stopped scheduler emits nothing).
3. Add a first-failure time to order, payment and refund failure evidence.
4. If horizontal scaling is planned: shared or partitioned discovery (Phase 3 recommendation), and a per-instance cap for replay and retention.
5. Consider `auto-commit=false` on the pool as a throughput item. It needs an app-wide transaction review.
6. Realign the 4 browser specs with the current UI (frontend owner).
