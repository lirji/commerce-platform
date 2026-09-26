# Tests and Regression Results

## 1. Full backend suite (`verify-final.log`, clean build, `mvn -B -o clean verify`)

**BUILD SUCCESS: 282 run, 278 passed, 0 failed, 4 skipped.**
- The skipped tests are the opt-in benchmarks: `EventSchedulingBenchmarkTest` (1) and `BackgroundRuntimeBenchmarkTest` (3).
- Baseline: 244 run, 243 passed, 0 failed, 1 skipped.

| Module | Run | Skipped |
|---|---|---|
| shared-kernel | 3 | 0 |
| marketing | 27 | 0 |
| order | 45 | 0 |
| commerce-app | 204 | 4 |
| architecture-tests (ModuleBoundaryTest) | 3 | 0 |

New Phase 3 test classes (38 tests):

| Class | Tests | Kind |
|---|---|---|
| `TenantRotationTest` | 9 | unit: fairness, wrap, lone tenant, poison tenant, visit isolation, breaker (injected clock), event fresh-lane breaker |
| `FailureSemanticsTest` | 4 | unit: taxonomy, cause chain/SQLState, transient set, retry delays/budgets |
| `EventFailureSemanticsTest` | 8 | DB: outage, breaker, business rejection + evidence + operator retry, mixed failure, retry storm, B1 ×3 |
| `OrderExpiryLaneTest` | 3 | DB: poison order, hot tenant, 4-instance exactly-once |
| `PaymentCheckLaneTest` | 3 | DB: channel outage, evidence conflict, cross-tenant breaker |
| `MemberPointsLaneTest` | 1 | DB: bulk expiry fairness |
| `PlatformRuntimeAuthorizationTest` | 5 | HTTP: B2 contract |
| `EventWorkerLaneTest` | 2 (rewritten) | lane failure isolation; real-scheduler starvation (3 vs 1 thread) |
| `BackgroundRuntimeBenchmarkTest` | 3 (opt-in) | benchmarks |

Changed existing tests:
- `EventHealthAlertTest`: new codes. The `Stats` and `Health` records gained fields.
- `EventRuntimeObservabilityTest`: `unrouted` now uses an undeclared type; lane metric tags added.
- `AuthorizationCoverageTest`: the platform namespace is treated as a staff namespace, and `PLATFORM_OPERATOR` is added to deny-by-default.

## 2. Regression matrix

| Area | Tests | Result |
|---|---|---|
| event consumer isolation | `EventConsumerIsolationTest` | pass |
| background task isolation | `EventWorkerLaneTest.failingLaneDoesNotSkipOtherLanes`, `WorkerResilienceTest` | pass |
| tenant fairness | `EventSchedulingFairnessTest` (6), `TenantRotationTest`, `OrderExpiryLaneTest`, `MemberPointsLaneTest` | pass |
| lane fairness | `EventWorkerLaneTest.slowLaneDoesNotStarveOtherLanes` | pass |
| order expiry | `WorkerResilienceTest.backgroundExpiry…`, `OrderExpiryLaneTest`, `PersistedCommerceTest` expiry cases | pass |
| payment closing/re-check | `WorkerResilienceTest.expiredInFlightPayment…`, `PaymentCheckLaneTest` | pass |
| refund processing | `PersistedCommerceTest` refund/aftersales flows | pass |
| deadlock / lock ordering | `PersistedCommerceTest` concurrency cases (R1 lock order) | pass |
| authorization | `AuthorizationCoverageTest`, `PlatformRuntimeAuthorizationTest` | pass |
| module boundaries | `ModuleBoundaryTest` (3) | pass |
| legacy quote decoding | `LegacyQuoteSnapshotTest` | pass |
| event health / alerts | `EventHealthAlertTest`, `EventRuntimeObservabilityTest` | pass |
| quarantine / operator retry | `EventSchedulingFairnessTest.poison…`, `EventFailureSemanticsTest.businessRejection…`, `OrderExpiryLaneTest.poisonOrder…` | pass |

## 3. Browser E2E (`e2e/browser-e2e.log`, `e2e/browser-results.json`)

- Setup: packaged jar with the current (uncommitted) UI on port 8606, test schema, workers on. Fixtures come from `prepare-e2e.py`, `seed-member-suite.py --fresh` and `seed-operations.py --fresh` against 8606. `no_proxy=127.0.0.1,localhost` was set only for the process; the system proxy was not changed.
- **17 passed / 4 failed of 21**, identical to the Phase 2 result. The failure messages match Phase 2 byte for byte (error text compared).

| Spec | Classification | Evidence |
|---|---|---|
| `catalog-merchandising` (member shop filter button `筛选商品` not found) | **existing**: uncommitted Shop rework | same locator timeout as Phase 2 |
| `coupon-deliveries` (expects `撤销 2`, UI renders `撤销 / 保留 2 / 0`) | **existing**: spec vs. committed UI | same text |
| `member-behavior` (`查看商品` button missing) | **existing**: uncommitted `Shop.tsx` | same locator |
| `member-cycles` (`color-scheme` light vs. dark) | **existing**: uncommitted `style.css` | same assertion |
| new failures | **none** | – |
| resolved failures | none | – |
| unrelated failures | the 4 above (frontend/spec, user-owned files not modified) | – |

- The backlog-sensitive `commerce.spec.ts` flows (order → sandbox payment → fulfilment → refund) passed while the workers were processing test-schema residue.
- The E2E run was repeated on the final clean build after the harness-defect discovery. The result was the same.
