# P3.6 / Load — Performance, Parallel Dispatch Experiment and Load Scenarios

**All numbers are local measurements** on the developer machine: macOS, Docker MySQL 8.4 (`innodb_flush_log_at_trx_commit=1`, 128 MB buffer pool), Java 21, Hikari pool 8, isolated schema `commerce_test_20260923` containing ~33k historical events and other residue. **They are not production capacity.**

| Kind | Harness | Raw data |
|---|---|---|
| micro benchmark | – (none; pure rules are unit-tested, not timed) | – |
| integration benchmark | `BackgroundRuntimeBenchmarkTest` (opt-in `-Dcommerce.runtime-benchmark=true`), `EventSchedulingBenchmarkTest` (Phase 2 harness, opt-in) | `runtime-benchmark-run1.jsonl`, `order-expiry-benchmark.jsonl`, `event-benchmark-phase3-aged*.jsonl` |
| real-handler runtime measurement | packaged jar, workers on, test schema (see `09-runtime-verification.md`) | `runtime-*.txt` |
| production capacity | **not measured** | – |

"Modeled" time = measured processing time + 1 s fixed delay per tick, which is the production cadence of a single lane.

## 1. Event lane regression check (Phase 2 harness, 10k aged backlog, same machine)

| Scenario | Phase 2 after: ticks / modeled | Phase 3: ticks / modeled | Max tenant first-attempt tick (P2 → P3) | Victim (new tenant) first attempt |
|---|---|---|---|---|
| single tenant 10k | 73 / 146 s | 47 / 94 s | 1 → 1 | – |
| balanced 20 × 500 | 65 / 130 s | 54 / 108 s | 2 → 1 | – |
| dominant 90% | 74 / 148 s | 48 / 96 s | 5 → 5 | 1 tick / 2.0 s → 1 tick / 2.0 s |
| many small 2,500 × 4 | 44 / 88 s | 50 / 99 s (repeat: see §1a) | 44 → 49 | 1 tick / 2.0 s → 1 tick / 2.0 s |
| 300 events, 1 / 3 consumers | 3.5 s / 8.0 s | 3.6 s / 7.7 s | 1 → 1 | – |

The fairness properties are unchanged: the victim is served on the next tick, and every tenant is served within one rotation. Drain time varies by ±15% between runs on this machine (§1a). No regression beyond that noise was found.

## 2. Parallel dispatch experiment (P3.6)

- Setup: 200 tenants × 25 events = 5,000 events, 2 consumers each (inbox insert + no-op).
- W independent dispatcher instances (the multi-instance model) run `tick()` back-to-back until the queue is empty. Two runs per W.

| Workers | Throughput (ev/s) | P50 / P95 time-to-delivered from start | Last tenant first attempt | Max DB connections | Row-lock waits | Duplicate executions (handler calls = inbox rows) |
|---|---|---|---|---|---|---|
| 1 | 130 / 117 | 14.8–16.9 s / 30.3–34.4 s | 7.8–9.5 s | 1 | 0 | 0 (10,000 = 10,000) |
| 2 | 192 / 184 | 9.9–10.2 s / 20.7–22.0 s | 5.1–5.9 s | 2 | 0 | 0 |
| 4 | 344 / 329 | 4.4–5.2 s / 9.8–11.5 s | 5.1–5.5 s | 4 | 0 | 0 |

- Deadlocks:
  - `INNODB_METRICS` needs the PROCESS privilege, which the app account does not have, so the metric reads "UNAVAILABLE".
  - The indirect evidence is 0. A deadlock would roll back a consumer transaction and log `event retry … CONCURRENCY_RETRYABLE`. No such line appeared, and handler calls equal inbox rows.

**Decision: do not enable intra-lane parallel dispatch.**
1. Throughput scales almost linearly (commit-bound, as the Phase 2 profile showed), but **the worst tenant wait barely improves beyond 2 workers** (≈ 5 s at both 2 and 4). Instances do not coordinate cursors, so they duplicate discovery. Fairness, the goal of this phase, does not gain.
2. Each worker holds one of the 8 pool connections. With the new 3-thread lane pool, adding 4 event workers per instance would leave ≤ 1 connection for HTTP under backlog. Pool pressure grows linearly (column "Max DB connections").
3. Correctness under parallelism is proven: 0 duplicates, 0 lock waits, the 4-worker test in the suite, and 4-instance order expiry. **Horizontal scaling (more app instances) is therefore safe.** It is the recommended lever when throughput is needed, together with a larger pool.
4. Cross-lane isolation, not intra-lane parallelism, was the measured problem (§3).

## 3. Cross-lane load (scenarios B and C, and slow external dependency)

- Setup: the real scheduler topology with 1 s fixed delay per lane.
  - Events lane: 5,000 aged events, 500 tenants, consumer ≈ 2 ms.
  - Orders lane: 1,500 expired orders, 150 tenants.
  - Payments lane: a new sandbox-PAID payment arrives every 2 s (18 in total).
- Window: 40 s.
- 1 thread = Phase 2 topology; 3 threads = Phase 3 default.

| Case | Payment confirmed after arrival: P50 / max | Expiry backlog drained | Events delivered in 40 s | Max start lag: orders / payments / events |
|---|---|---|---|---|
| 1 thread | 1,595 / 1,623 ms | **not within 40 s** | 2,589 | 512 / 1,013 / 5 ms |
| 3 threads | **321 / 1,030 ms** | **38.1 s** | 2,652 | 5 / 8 / 5 ms |
| 1 thread + slow channel (payments run +2 s) | 1,796 / 3,543 ms | **not within 40 s** | 1,710 | **2,042** / 511 / **1,544** ms |
| 3 threads + slow channel | 1,280 / 3,042 ms | **36.6 s** | 2,732 | 10 / 7 / 10 ms |

Reading:
- On one thread, an event backlog delays order expiry and payment confirmation. A slow payment channel delays every other lane by its full duration, cutting event delivery by 34%.
- On three threads, unrelated lanes are unaffected. Their lag is ≤ 10 ms and their throughput is the same with or without the slow channel. The slow lane only slows itself: payment P50 is 1.3 s, bounded by its own 2 s call.

## 4. Order expiry tenant fairness (scenario D: hot tenant, old vs new algorithm)

- Setup: 1 hot tenant with 2,000 expired orders plus 300 small tenants with 1 each.
- "Old" is emulated faithfully with the admin expire command: one transaction of ≤ 20 orders per tenant, 4 tenants per tick, lexicographic cursor.

| Algorithm | Ticks until every small tenant is served | Modeled time until every small tenant is served | Hot-tenant orders before the last small tenant | Ticks to drain everything | Modeled drain |
|---|---|---|---|---|---|
| old: 4 tenants per tick | 76 | 80.1 s | 20 | 274 | 289.7 s (7.9 orders/s) |
| new: rotation | **5** | **7.5 s** | 20 (see note) | **43** | **64.4 s (35.7 orders/s)** |

This benchmark samples after each tick. In the new algorithm, the tick that serves the last small tenants wraps around and visits the hot tenant a second time before the sample is taken, so it shows 2 × quantum. The exact ordering, measured by event commit order, is asserted in `OrderExpiryLaneTest.hotTenantCannotStarveOtherTenants`: ≤ 10 (1 quantum).

The first run of this benchmark had a seeding bug: hot orders with index > 1,200 had a future `expires_at`, so neither algorithm could drain and both stopped at the 5,000-tick cap. The "small tenants served" columns above come from that run and are valid (every small order had index 0). The drain columns come from the corrected re-run (`order-expiry-benchmark.jsonl`).

## 5. Load scenario coverage

| Scenario | Where proven | Result |
|---|---|---|
| A 10k historical events + new event | event benchmark (victim) | new tenant served next tick (≈ 2.0 s modeled) |
| B large event backlog + order expiry | cross-lane benchmark | 3 threads: expiry lag ≤ 10 ms, backlog drained in ~37 s; 1 thread: not drained in 40 s |
| C large expiry backlog + payment reconciliation | cross-lane benchmark | payment P50 321 ms (3 threads) vs 1,595 ms (1 thread) |
| D one hot tenant + many small | `OrderExpiryLaneTest`, `MemberPointsLaneTest`, `TenantRotationTest`, expiry benchmark, event benchmark | hot tenant ≤ 1 quantum before every small tenant is served |
| E temporary DB outage + healthy pending events | `EventFailureSemanticsTest.temporaryOutage…` (pool-exhaustion simulation), `TenantRotationTest.discoveryFailure…`, real TCP outage in `09-runtime-verification.md` | no healthy event quarantined; breaker; recovery |
| F poison business event + healthy events in the same tenant | Phase 2 poison test, `OrderExpiryLaneTest.poisonOrder…` | healthy work proceeds; poison bounded to 5 attempts with evidence |
| G poison tenant + healthy tenants | `TenantRotationTest.poisonTenant…`, `visitExceptionIsIsolated…` | only the poison tenant's visits are lost; no breaker trip |
| H retry storm + fresh traffic | `EventFailureSemanticsTest.retryStormDoesNotDelayFreshEvents` | fresh event delivered first in the tick despite 300 due retries |
