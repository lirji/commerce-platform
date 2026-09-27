# P4.7 — Crash and Restart Recovery

## 1. Method

- **Crash injection (`CrashRecoveryTest`).** The real transaction manager is wrapped so that it throws an `Error` at the n-th `getTransaction`, or rolls back and throws at the n-th `commit`.
  - Business code catches only `RuntimeException`, so an `Error` behaves like process death: nothing after the injection point runs, the failure is not recorded, and uncommitted work is rolled back by the database.
- **Restart** = a brand-new `EventDispatcher` / `EventReplay` instance against the same database: no cursor, no breaker, no statistics.
- Effects are counted as real rows written by the consumer in its own transaction, one per execution. The assertions are about **side effects**, not only scheduler state.
- **Live restart (§3).** The packaged jar is killed with `kill -9` while a real backlog is being processed, then started again (`scripts/live-soak.py`, part A).

## 2. Scenarios C1–C6 (all pass)

| # | Injection point | State after the crash | After restart |
|---|---|---|---|
| C1 | before the first work transaction starts (`getTransaction` #1) | all events PENDING, `attempts=0`, 0 effects | all DELIVERED, each effect exactly 1 |
| C2 | during DB work: the consumer effect was written but the commit failed (commit #1) | effect **and** inbox rolled back, event PENDING, `attempts=0` | DELIVERED, effect exactly 1 |
| C3 | after the business change, before the progress update (replay: the second item's commit) | job `examined=1, executed=1`; exactly 1 effect row (the committed item) | job COMPLETED 3/3; each event's effect exactly 1. The effect and the cursor are one transaction, so they cannot diverge. |
| C4 | after consumer c0 committed, before c1 started (`getTransaction` #2) | c0 effect 1, c1 effect 0, event PENDING, `attempts=0` | c1 runs once, **c0 is not re-run** (inbox), DELIVERED (R7) |
| C5 | while the retry state is being updated: the failure-record transaction (begin #2, or commit #1 after the consumer rolled back) | event PENDING, `attempts=0`, `last_error` null. The failure was lost, but no counter is half-written (single atomic UPDATE). | the healthy consumer delivers exactly once |
| C6 | restart with a backlog: 20 tenants × 10 events, plus an ISOLATED event; instance A tripped its breaker and "crashed" | breaker state existed only in A | B starts with the breaker closed. B's **first tick visits all 20 tenants** (fairness resumes without the old cursor), B drains everything exactly once, the ISOLATED event stays ISOLATED, and `attempts=0` on all (transient failures did not consume the poison budget) |

The same property for member items is structural:
- a points expiry or cycle assessment, its scheduling update (`cycle_due_at`) and the deletion of its retry row commit in one transaction;
- the failure record is a single atomic `INSERT … ON DUPLICATE KEY UPDATE`.

## 3. Live kill -9 (`16-capacity-soak.md` §1, `live/live-soak.json`)

- The packaged jar was processing 2,000 expired point lots and 500 due cycle assessments. It received SIGKILL (no shutdown hook) after 213 lots and 184 assessments had committed.
- After restart, all 2,000 lots were expired with **exactly one `EXPIRE` ledger row each** (0 duplicates; the ledger has no unique key, so a duplicate would be visible), `SUM(expired)=20,000`, and 500 accounts and 500 `member.cycle.assessed` events existed.
- The in-flight transactions were rolled back by MySQL when the connection dropped. Nothing depended on process memory.

## 4. Restart guarantees (§18)

| Guarantee | Why it holds | Evidence |
|---|---|---|
| pending work stays discoverable | discovery queries read durable status and due columns only | C1, C6, live restart |
| retry eligibility preserved | `available_at`, `retry_at`, `expiry_retry_at` and `next_check_at` are durable | C5, `ItemRetryIsolationTest` |
| successful consumers not re-executed | inbox PK | C4, C6 |
| expired breaker state not stuck | the breaker is process-local, starts closed, and has a cool-down of at most 1 min in-process | C6 |
| no rotation cursor needed for correctness | the cursor only orders visits; discovery starts from `""` | C6 (first tick visits all tenants) |
| lane fairness resumes | the same rotation contract as Phase 3 | C6 |
| quarantine intact | a durable status | C6 |
| operator recovery state auditable | `platform_recovery` is written in the command transaction | 04 |

**Process-local state that is reset on restart (optimization only):**
- rotation cursors, breakers and lane statistics;
- `WorkLanes` backlog cache, retention lag cache;
- event health log counters.

None of them is needed for correctness.
