# P4.10 — Regression and Closure

## 1. Backend suites

| Run | Command | Result |
|---|---|---|
| baseline (P4.0) | `scripts/verify.sh` | 282 run / 278 passed / 0 failed / 4 skipped (`baseline-verify.log`) |
| mid-phase | `scripts/verify.sh` | 327 run / 323 passed / 0 failed / 4 skipped |
| **final, clean build with UI** | `scripts/build.sh`, i.e. `npm ci`, `npm run build`, `mvn -B -Pwith-ui clean verify`, plus the jar UI check | **BUILD SUCCESS: 329 run / 324 passed / 0 failed / 5 skipped** (`final-verify.log`); per module: shared-kernel 3, marketing 27, order 45, commerce-app 251 (5 skipped), architecture-tests 3 |

The 4 skipped tests are opt-in benchmarks, 5 with the new opt-in `EventDispatchProfileTest`.

New Phase 4 test classes:

| Class | Tests |
|---|---|
| `ItemRetryIsolationTest` | 8 |
| `CycleScheduleTest` | 5 |
| `RuntimeRecoveryTest` | 5 |
| `ReplayTest` | 10 |
| `RetentionTest` | 7 |
| `CrashRecoveryTest` | 6 |
| `MultiInstanceRecoveryTest` | 4 |
| `OperationalAlertTest` | 1 |
| `EventDispatchProfileTest` | 1, opt-in |

Changed Phase 3 tests:
- `EventRuntimeObservabilityTest`: the fixed lane set now includes `replay` and `retention`; it also asserts points/cycles quarantine gauges and retention tag cardinality.
- `WorkerResilienceTest`: cleanup of `member_work_retry` rows.

## 2. Regression matrix (§54)

| Area | Evidence (all pass in the final run) |
|---|---|
| event fairness | `EventSchedulingFairnessTest`, benchmark (14) |
| tenant rotation | `TenantRotationTest`, `CrashRecoveryTest.c6…` (first tick after restart visits all tenants) |
| cross-lane fairness | `EventWorkerLaneTest` (12 lanes registered in production; the test builds the 10 business lanes) |
| event consumer isolation | `EventConsumerIsolationTest` |
| background-task isolation | `WorkerResilienceTest`, `ItemRetryIsolationTest` |
| order expiry | `OrderExpiryLaneTest`, `WorkerResilienceTest` |
| payment confirmation | `PersistedCommerceTest`, `PaymentCheckLaneTest` |
| payment close/re-check | `WorkerResilienceTest.expiredInFlightPaymentIsClosed…`, `PaymentCheckLaneTest` |
| refunds | `PersistedCommerceTest` (refund flows), `PaymentCheckLaneTest` |
| points | `MemberPointsLaneTest`, `ItemRetryIsolationTest`, `MultiInstanceRecoveryTest.backgroundAndOperatorExpiry…` |
| cycles | `MemberCycleTest`, `CycleScheduleTest`, `ItemRetryIsolationTest` |
| legacy quote decode | `LegacyQuoteSnapshotTest` |
| temporary DB outage | `EventFailureSemanticsTest.temporaryOutage…`, `ItemRetryIsolationTest.transientLockTimeout…`, live soak outage (16) |
| breaker behaviour | `TenantRotationTest`, `EventFailureSemanticsTest.dependencyOutage…`, `CrashRecoveryTest.c6…` |
| quarantine | `EventFailureSemanticsTest`, `ItemRetryIsolationTest` |
| operator retry | `EventFailureSemanticsTest` (legacy), `RuntimeRecoveryTest` |
| item-level retry | `ItemRetryIsolationTest` |
| recovery | `RuntimeRecoveryTest`, `MultiInstanceRecoveryTest` |
| replay authorization | `RuntimeRecoveryTest.recoveryAndReplayAuthorizationMatrix` |
| replay dedup | `ReplayTest.unprocessedReplayRestoresTheProjectionExactlyOnce`, `…twoInstances…` |
| retention | `RetentionTest` |
| deadlock / lock ordering | Phase 3 lock-order tests in `PersistedCommerceTest`; the new paths take one row lock per transaction (job, then event), or one member or retry row |
| multi-instance execution | `EventSchedulingFairnessTest.concurrentWorkersNeverProcessAConsumerTwice`, `OrderExpiryLaneTest.concurrentInstances…`, `ReplayTest.twoInstances…`, `MultiInstanceRecoveryTest` |
| 401/403/404 | `PlatformRuntimeAuthorizationTest`, `AuthorizationCoverageTest`, `RuntimeRecoveryTest.recoveryAndReplayAuthorizationMatrix` |
| platform-operator boundaries | `PlatformRuntimeAuthorizationTest`, the matrix above |
| module architecture | `ModuleBoundaryTest` (3); controllers stay thin (`RuntimeRecoveryController` only delegates) |

## 3. Mutation / negative tests (§37) — `scripts/mutate.py`, `mutation/mutation-results.json`, per-mutation logs in `mutation/`

Each mutation removes one protection. The harness then reinstalls only that module (`mvn -o install -pl <module>`), runs the targeted tests, restores the file, checks its SHA-256 against the original, touches its mtime (the Phase 3 harness lesson) and reinstalls the module.

**18/18 must-detect mutations were detected.** Of the 3 mutations predicted as possible survivors:
- `recovery-resolved-without-cas` survived as predicted: the compare-and-set on the delete still rejects the second recovery;
- `allow-retention-to-delete-pending` survived as predicted: the selection query still filters by status;
- `event-recovery-without-row-lock` was **detected** by the 10-round SKIP/RETRY race test, which confirms the stale-audit race fixed in 10 is real and covered.

| # | Mutation | Protection removed | Expected | Result | Test |
|---|---|---|---|---|---|
| 1 | `remove-points-item-retry-isolation` | item-level retry isolation (points) | detect | **detected** | `ItemRetryIsolationTest#moreBadLotsThanOneQuantumNoLongerBlockTheTenant+badLotBacksOffAloneAndIsQuarantinedAfterFiveFailures` |
| 2 | `remove-cycles-item-retry-isolation` | item-level retry isolation (cycles) | detect | **detected** | `ItemRetryIsolationTest#badMembersDoNotBlockAssessmentOfHealthyMembers+poisonCycleTenantDoesNotDelayOtherTenants` |
| 3 | `count-transient-as-permanent-item` | transient failures counted as permanent | detect | **detected** | `ItemRetryIsolationTest#transientLockTimeoutDoesNotConsumeThePoisonBudget` |
| 4 | `keep-retry-row-after-success` | retry state cleared on success | detect | **detected** | `ItemRetryIsolationTest#independentFailingLotsKeepTheirOwnRetryState` |
| 5 | `skip-policy-rollout` | policy rollout | detect | **detected** | `CycleScheduleTest#newPolicyRollsOutInBoundedBatchesAndEveryMemberIsReassessed` |
| 6 | `cycles-probe-sorts-whole-tenant` | query performance gate (cycles probe) | detect | **detected** | `CycleScheduleTest#criticalDiscoveryQueriesKeepTheirIndexPlans` |
| 7 | `bypass-replay-hard-rule` | replay safety gate hard rule | detect | **detected** | `ReplayTest#safetyGateFailsClosed` |
| 8 | `bypass-gate-at-creation` | replay safety gate at creation | detect | **detected** | `ReplayTest#sensitiveConsumersAreRejectedBeforeAnyWork` |
| 9 | `bypass-gate-at-execution` | replay safety gate at execution | detect | **detected** | `ReplayTest#executionTimeGateStopsARunningJobWhenTheClassificationChanges` |
| 10 | `reexecute-processed-consumer-in-replay` | re-execute successful consumer during replay | detect | **detected** | `ReplayTest#unprocessedReplayRestoresTheProjectionExactlyOnce` |
| 11 | `remove-replay-live-yield` | replay yields to live work | detect | **detected** | `ReplayTest#replayYieldsToLiveWork` |
| 12 | `remove-replay-job-lock` | cross-instance lock on replay | detect | **detected** | `ReplayTest#twoInstancesAdvanceOneJobWithoutDuplicateExecution` |
| 13 | `remove-recovery-authorization` | recovery authorization | detect | **detected** | `RuntimeRecoveryTest#recoveryAndReplayAuthorizationMatrix` |
| 14 | `remove-recovery-audit` | recovery audit | detect | **detected** | `RuntimeRecoveryTest#quarantinedLotRecoveryIsExplicitAuditedIdempotentAndPreservesEvidence` |
| 15 | `recovery-resolved-without-cas` | recovery row lock (second guard: delete CAS) | may survive | survived | `MultiInstanceRecoveryTest#concurrentRecoveriesOfOneItemApplyExactlyOnceAndAreAllAudited` |
| 16 | `allow-retention-to-delete-pending` | retention delete status filter (second guard: selection filter) | may survive | survived | `RetentionTest#onlyProvablySafeDataIsPurged` |
| 17 | `retention-selects-unfinished-events` | retention selects pending/isolated rows | detect | **detected** | `RetentionTest#onlyProvablySafeDataIsPurged` |
| 18 | `delete-dedup-beyond-terminal-events` | delete dedup before retention-safe boundary | detect | **detected** | `RetentionTest#onlyProvablySafeDataIsPurged` |
| 19 | `retention-ignores-active-replay` | retention respects active replay | detect | **detected** | `RetentionTest#activeReplayRangesAreRetained` |
| 20 | `retention-zero-floor` | no zero-retention configuration | detect | **detected** | `RetentionTest#retentionIsDisabledByDefaultAndRefusesDangerousConfiguration` |
| 21 | `event-recovery-without-row-lock` | event recovery row lock (audit accuracy) | may survive | **detected** | `MultiInstanceRecoveryTest#concurrentSkipAndRetryProduceOnlyValidTransitions` |

**Invalid first result for #6, corrected.**
- The filesort assertion was added to the plan gate just before the mutation run, and was never run against the *unmutated* SQL.
- The first "detected" for `cycles-probe-sorts-whole-tenant` was therefore not valid: the check descended into nested subqueries and flagged the outer sort of the small union result.
- The final clean build exposed this, because the gate failed on correct code.
- Fix: the check now only inspects the tables directly sorted by each `ordering_operation`.
- Re-validated in both directions:
  - the unmutated gate passes;
  - the mutation is detected with the message `周期发现探测不得对会员排序` ("the cycle discovery probe must not sort members") — `mutation-rerun/`.
- Every other mutation's target test passed unmutated in the full suite, so their results stand.

**Harness hygiene finding.** The first run kept each backup next to the source (`X.xml.mutation-backup`). Maven's resource copy put 4 of these files into `target/classes`. MyBatis loads only `mappers/**/*.xml`, so they were never loaded, and they contained the original text. The final artifact was built with `clean` (§5), and the jar contains no backup file (checked). The harness now keeps its backups in the output directory.

## 4. Browser regression (`e2e/*-browser.log`, `e2e/<run>/browser-results.json`)

Same setup as Phase 3 (`scripts/browser-e2e.sh`): the final clean jar on 8606, test schema, workers on, fresh fixtures for every run.

| Run | Passed | Failed specs |
|---|---|---|
| baseline (start of Phase 4) | 17/21 | catalog-merchandising, coupon-deliveries, member-behavior, member-cycles |
| final | 16/21 | the 4 above **+ operations "旅程与效果"** (journeys and effects) |
| final-r2 | **17/21** | the 4 above |
| final-r3 | **17/21** | the 4 above |

| Spec | Classification |
|---|---|
| catalog-merchandising, coupon-deliveries, member-behavior, member-cycles | **existing**. The failure messages of final-r2 are byte-identical to the baseline (sorted error lines diffed). |
| operations `旅程与效果：升级通知、成交退款和历史补齐` (journeys and effects: upgrade notice, paid-refund, backfill) | **intermittent, not reproduced (1 of 3 runs).** `POST /aftersales` returned 409 because the order's fulfillment hold did not exist yet. The spec's `events()` helper stops as soon as one manual `/admin/events/pump` returns 0. With workers on, the background event lane may hold the next event row at that moment (`SKIP LOCKED`), so the helper can return before `payment.paid → order.paid → fulfillment` has finished. This race exists in the spec regardless of Phase 4. Phase 4 adds two lanes to the same 3-thread scheduler, which can shift that timing, so a timing influence cannot be fully excluded. Phase 4 did not change any product code on that path (payment, order, fulfillment and aftersale logic are unchanged except for the replay classification methods). |

New regressions caused by Phase 4: **none reproduced.** Resolved: none.

## 5. Final clean build and artifact hygiene

- The final artifact comes from `scripts/build.sh`, which runs `mvn … clean verify`. All `target/` directories are rebuilt from the current sources.
- It was run **after** the mutation runs and after every mutated file was restored and verified by SHA-256 (harness assertion).
- `find . -name '*.mutation-backup'` finds nothing, and the jar contains no backup file.
- Spot check of the packaged mapper XML inside `BOOT-INF/lib/member-*.jar`:
  - `CycleMapper.xml` has the full-prefix probe `ORDER BY m.tenant_id,m.cycle_due_at LIMIT 1`;
  - `PointsMapper.xml` has the retry-block filter.
  - These are exactly the constructs that mutations 1 and 6 removed, so the packaged artifact carries the protected versions.
- The jar contains the UI (`BOOT-INF/classes/static/index.html`, checked by `build.sh`).
