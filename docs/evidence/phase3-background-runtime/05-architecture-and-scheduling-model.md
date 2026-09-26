# P3.1–P3.4 — Architecture Changes and Scheduling Model

## 1. Topology (before → after)

```text
BEFORE (Phase 2)                                   AFTER (Phase 3)
@Scheduled(fixedDelay=1s) deliver()                ThreadPoolTaskScheduler "commerce-lane-" (commerce.worker-threads, default 3, 1..6)
  one thread, 10 lanes in sequence                   10 independent fixed-delay(1 s) tasks + 1 health task (60 s)
  lane cost adds to every other lane's period        queue ordered by next trigger time (DelayedWorkQueue)
                                                     LaneMonitor: runs, failures, start lag, run time per lane
```

- **Lane fairness contract.** Every lane run is bounded by its own budget. A lane that is due runs as soon as one of the K threads is free. Due tasks are taken in trigger-time order, so no lane can be passed over indefinitely. The worst-case start lag is about ⌈(L−1)/K⌉ × (longest lane run). With L=10 lanes, K=3 threads and budgets ≤ 1 s (+ one in-flight item), that is ≈ 3 s.
- **No re-entrancy.** Fixed-delay scheduling re-arms a lane only after its run ends. `TenantRotation.run` is `synchronized` for manual/test callers.
- **Connection budget.** Each lane thread holds at most one DB connection at a time. All work in a run is sequential: a channel call outside a transaction, then one transaction. K=3 of the 8-connection Hikari pool leaves 5 for HTTP threads. K is capped at 6 in code.
- **Shutdown.** `setWaitForTasksToCompleteOnShutdown(true)` with 15 s await. The Spring lifecycle stops the scheduler, and in-flight lane runs finish their current item. Every item transaction has a 10 s timeout, and no claim outlives its transaction (Phase 2 §6). An interrupted run therefore leaves no state to expire.

Measured (`EventWorkerLaneTest.slowLaneDoesNotStarveOtherLanes`, real scheduler, events lane sleeping 2.5 s per run, 7 s window):

| Topology | Other lanes: max start lag | Runs in 7 s |
|---|---|---|
| 1 thread (Phase 2) | ≥ 2,000 ms (asserted) | ~3 |
| 3 threads (Phase 3) | < 700 ms (asserted) | ≥ 5 |

## 2. Shared primitives (platform-runtime, the narrowest layer every module may depend on)

| Primitive | Responsibility | Used by |
|---|---|---|
| `TenantRotation` (+ `Policy`, `Run`, `Stats`) | Cursor rotation over tenants with due work: discovery batches, a per-visit quantum, a per-run time/item budget, wrap-around until the budget is spent or a full pass makes no attempt, a lone-tenant fast path, per-tenant failure isolation, and the dependency breaker | events, orders, payments, refunds, points, cycles, segments, journeys, deliveries, catalog-jobs |
| `FailureClass` | Structured failure taxonomy | all 10 lanes |
| `RetryPolicy` | Bounded exponential backoff with injected jitter; POISON / TRANSIENT budgets; deadline-bound deferral | events, orders, payments, refunds, item lanes |
| `WorkLanes` | Registry of lane rotations and optional backlog suppliers (cached 5 s), so the app reads lane health without cross-module mapper access | 9 lanes (events exposes its own) |
| `UnconsumedEventType` | Publisher declaration of intentionally unhandled event types (B1) | order-runtime → Outbox, EventDispatcher |

Why no bigger framework:
- Tenant iteration and the budget are the only parts that repeated exactly in 8 lanes.
- Item selection, transactions, claims and retry state stay in each lane, because their semantics differ: event inbox, order CAS, payment claim-then-call, job receipts.

App shell (`commerce-app`):
- `EventWorker` (topology), `LaneMonitor` (schedule observations), `BackgroundRuntime` (platform view and lane alert rules), `PlatformRuntimeController`, `BackgroundLaneMetrics`.
- `ModuleBoundaryTest` passes: the app reaches domains only through `.api` and `runtime`.

## 3. Per-lane fairness contracts

Rotation window (worst wait of a tenant with due work) ≈ Σ over tenants of min(dueᵢ, quantum) × c / duty, where c is the per-item cost and duty = budget / (budget + 1 s delay). It is observable as `rotation.lastRotationMillis`, and `LANE_ROTATION_SLOW` fires above 5 min.

| Lane | Quantum / visit | Budget / run | Per-item cost (local, measured or observed) | Fresh work | Backlog | Empty tenant | Single hot tenant | Evidence for values |
|---|---|---|---|---|---|---|---|---|
| events | 5 events | 2,000 or 1 s | ≈ 4 ms per consumer (Phase 2 profile) | fresh lane (never-failed events due in the last 10 s), ≤ ½ budget | rotation (incl. due retries) | not discovered (index range on due rows) | drains continuously | Phase 2 benchmarks; unchanged |
| orders (expiry) | 10 orders | 200 or 500 ms | ≈ 9–13 ms/order (5 locks + outbox; cross-lane run: 1,500 orders in ~27 runs × 500 ms) | rotation only: expiry is a deadline, not latency-critical past it | rotation | not discovered | drains continuously | 10 × ~10 ms ≈ 100 ms per visit keeps ≥ 5 tenants per run |
| payments (check/close) | 5 checks | 100 or 1 s | one channel call (sandbox ≈ 5–30 ms; remote unknown) | rotation; a new UNKNOWN is due immediately | rotation | not discovered | drains continuously | quantum 5 = previous per-tenant limit; 1 s bounds remote latency |
| refunds (check) | 5 | 100 or 1 s | as payments | as payments | rotation | – | – | same channel profile |
| points (expiry) | 20 lots | 200 or 500 ms | several row locks + ledger insert | rotation (was global FIFO) | rotation | – | drains continuously | 20 = previous batch size |
| cycles (assessment) | 10 members | 200 or 500 ms | 2 SUMs + upsert + outbox | rotation (was global FIFO) | rotation | – | – | heavier item than points |
| deliveries | 20 recipient steps | 200 or 500 ms | 1 coupon grant | rotation | rotation | – | – | 20 = previous per-visit steps |
| catalog-jobs | 20 SKU steps | 200 or 500 ms | 1 product change | rotation | rotation | – | – | 20 = previous |
| segments | 3 phase steps | 30 or 500 ms | a batch is 100 members | rotation | rotation | – | – | a step is already a 100-member batch |
| journeys | 25 (20 scan + 5 nodes) | 200 or 500 ms | one node transaction | rotation | rotation | – | – | previous per-visit bounds |

Values were **not** copied from the event lane: quantum, time budget and freshness differ per lane, for the reasons in the last column.

## 4. Cross-lane fairness model

| Dimension | Mechanism | Evidence |
|---|---|---|
| tenant | `TenantRotation` per lane | `TenantRotationTest`, `OrderExpiryLaneTest.hotTenant…`, `MemberPointsLaneTest`, Phase 2 event tests |
| lane | independent fixed-delay tasks on a K-thread pool, FIFO by trigger time | `EventWorkerLaneTest`, cross-lane benchmark |
| item | per-item transactions; a failing item backs off; FIFO within a tenant | `OrderExpiryLaneTest.poisonOrder…`, Phase 2 poison test |
| retry | retries leave the fresh lane (events); transient failures do not consume poison budgets; breaker instead of burn-down | `retryStormDoesNotDelayFreshEvents`, `dependencyOutage…`, `channelOutageAcrossTenants…` |

Questions from the phase brief, answered with measurements (`08-performance-and-load.md`):
- **Can an event backlog delay order expiry?** Before (1 thread): yes, orders ran every ~2 s and the 1,500-order expiry backlog did not drain in 40 s. After (3 threads): the orders lane start lag was ≤ 10 ms and the backlog drained in 36–38 s.
- **Can order expiry delay payment reconciliation?** Before: P50 payment confirmation 1.6 s. After: 0.32 s.
- **Can slow external payment calls delay unrelated work?** With a 2 s slow channel on 1 thread, the orders lane lag rose to 2,042 ms and the events lane lag to 1,544 ms. On 3 threads, both stayed ≤ 10 ms.
- **Can one pathological tenant delay all lanes?** Within a lane, it gets one quantum per visit; its failures back off, and its visit exceptions are isolated. Across lanes it can only use its own lane's budget.

## 5. Concurrency / claiming model per migrated lane

| Lane | Claim | Crash after claim | Double claim (multi-instance) |
|---|---|---|---|
| events | per-consumer tx: `FOR UPDATE SKIP LOCKED` + inbox | tx rollback; inbox makes a committed consumer idempotent | SKIP LOCKED; inbox (4-worker test) |
| orders | per-order tx: `expiredLock … FOR UPDATE SKIP LOCKED` rechecks the due condition | rollback; the order stays due | 4 independent instances × 400 orders: each cancelled exactly once, 400 events (`concurrentInstancesExpireEachOrderExactlyOnce`) |
| payments/refunds | committed claim (`check_attempts` CAS + `next_check_at`), channel call outside tx | the claim delays the next check; the channel request is idempotent | CAS on `check_attempts` |
| points/cycles | per-item tx with blocking member lock + re-check | rollback | the second instance re-checks under the lock (no-op) |
| item lanes | unchanged per-step tx + version checks | unchanged | unchanged |
