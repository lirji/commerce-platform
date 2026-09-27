# P4.1 — Item-Level Failure Isolation (points expiry, cycle assessment)

## 1. Execution model before Phase 4 (investigated from source)

- **Points** (`MemberPointsService.tick`):
  - rotation visit → `PointsMapper.due(tenant, now, quantum=20)`, which returns the oldest due lots in `(expires_at, lot_id)` order;
  - one transaction per lot. A failure was logged and counted by the lane and the loop continued, but **no per-lot state was written**.
  - A permanently failing lot was therefore selected again on every visit (every second). If a tenant had ≥ 20 such lots at the head of its order, every visit took only failing lots, so the tenant's healthy lots **never expired**. That is limitation L1.
- **Cycles** (`MemberCycleService.tick`): the same shape, with quantum 10 and ordering by the due boundary. A member whose assessment always fails (for example corrupt contributions) was selected again every visit. ≥ 10 of them blocked the tenant.
- Other lanes already have item state: events, orders, payments, refunds, segments, journeys, deliveries and catalog jobs (Phase 3 document 06). They were not changed.

## 2. Design

- **Separate retry state, not new scheduling state.**
  - The business due time stays where it was (`member_point_lot.expires_at`, and for cycles the new `member_record.cycle_due_at`; see 03).
  - A new member-owned table `member_work_retry(tenant_id, lane, item_id)` exists **only while an item is failing**, and is deleted in the same transaction as the item's success.
  - Nothing is duplicated: normal items have no row.
- **Columns** (V38):
  - `attempts` (non-transient), `transient_attempts`, `retry_at`;
  - `failure_class`, `last_error` (class and exception type only, ≤ 160 chars, no payload or message);
  - `first_failed_at`, `last_failed_at`, `quarantined_at`, `manual_recoveries`.
- **Budgets** are the Phase 3 `RetryPolicy` values:
  - POISON: 2/4/8/16 s, quarantined at the 5th non-transient failure.
  - TRANSIENT: 2 s doubling to 5 min, quarantined at 300 (≈ 27 h).
  - Transient failures never increment `attempts`.
- **Atomic accounting.** A single `INSERT … ON DUPLICATE KEY UPDATE` increments the right counter and sets `quarantined_at` when either budget is reached (`COALESCE` keeps the first quarantine time). Concurrent failures from two instances cannot lose an increment (`MultiInstanceRecoveryTest.concurrentFailureRecordingNeverLosesCounts`: 2 × 50 → exactly 100).
- **Selection.**
  - The due queries skip items whose retry row is in backoff (`retry_at > now`) or quarantined.
  - The due query `LEFT JOIN`s the retry row by primary key, so the lane knows the current counters without an extra query.
  - Points discovery keeps `FORCE INDEX(ix_point_tenant_expiry)`. The per-tenant query now also forces it: the query-plan gate found that without it the optimizer can choose `PRIMARY`, which scans the tenant's whole lot history.
- **Failure recording** happens in its own transaction after the item's transaction rolled back (the same pattern as order expiry):
  - If recording itself fails, the item simply stays due and is tried again. No work is lost; only the counter increment is lost (C5 in 09).
- **Success** deletes the retry row inside the business transaction:
  - `MemberPointsService.expireLot` deletes it for every path that expires a lot, including the admin `expire` command and refund-driven expiry, so no orphan rows are left.
  - `MemberCycleService.assessLocked` deletes it for every successful assessment path (lane, admin `evaluate`, growth transaction).
- The **transient path is unchanged**: a transient failure ends the tenant visit and counts toward the lane breaker.

## 3. Isolation requirements → tests (`ItemRetryIsolationTest`, 8 tests, all pass)

| Brief scenario | Test | What is asserted |
|---|---|---|
| bad item stays alone and is bounded | `badLotBacksOffAloneAndIsQuarantinedAfterFiveFailures` | 3 healthy lots expire; the bad lot (an order-less "ghost" member, which gives `NOT_FOUND` = BUSINESS_REJECTED) has `attempts=1`, the class, `first_failed_at` and `retry_at` in the future; 5 more ticks during backoff do not retry it; after 5 failures it is quarantined, `first_failed_at` is unchanged, and it is never selected again (R5) |
| **P0: more bad items than one quantum** | `moreBadLotsThanOneQuantumNoLongerBlockTheTenant` | 25 bad lots older than 5 healthy lots: the healthy lots expire within a few ticks, and each bad lot failed exactly once. Before Phase 4 the healthy lots never expired (see the mutation M1 in 15) |
| Scenario 1: tenant A bad + healthy | first test above | – |
| Scenario 2: tenant A permanently failing, tenant B healthy backlog | `permanentlyFailingTenantDoesNotDelayAnotherTenant` | B's 30 lots drain; A's lot is attempted ≤ 2 times meanwhile |
| Scenario 3: several independent failing items interleaved with healthy ones | `independentFailingLotsKeepTheirOwnRetryState` | all healthy lots expire; each bad lot has its own row; fixing one (adding the member) makes it expire and deletes only its row |
| Scenario 4: transient outage + item state | `transientLockTimeoutDoesNotConsumeThePoisonBudget` | a real row lock held by another connection (lock wait 1 s) → `CONCURRENCY_RETRYABLE`, `attempts=0`, `transient_attempts=1`; after release the lot expires and the row is deleted |
| budgets counted separately and atomically | `retryBudgetsAreCountedSeparatelyAndAtomically` | 6 transient + 4 poison → not quarantined; the 5th poison → quarantined; 300 transient → quarantined |
| cycles: bad members more than one quantum | `badMembersDoNotBlockAssessmentOfHealthyMembers` | 12 members whose contributions overflow `long` (classified `PERMANENT`: the driver reports a `DataIntegrityViolationException`) come first; the 3 healthy members are assessed; 12 retry rows |
| cycles: poison tenant vs healthy tenant | `poisonCycleTenantDoesNotDelayOtherTenants` | a tenant with a `periodDays=0` policy fails; the other tenant is assessed; the failed member is not selected again during backoff |

The Phase 3 tests `MemberPointsLaneTest`, `WorkerResilienceTest.poisonCycleRowDoesNotStopOtherMembersInSameTick` and `MemberCycleTest` pass unchanged. `WorkerResilienceTest` only gained a cleanup line for the new table.

## 4. Invariants

- **R1:** one permanently bad item cannot indefinitely block unrelated healthy items. After its first failure it is out of the due set for ≥ 2 s (backoff), and after 5 failures permanently (quarantine).
- **R2:** tenant rotation is unchanged (Phase 3); the bad tenant's visit costs at most one quantum of attempts per backoff window.
- **R4:** transient failures never increment `attempts` (the lane level and the SQL level are both tested).
- **R5:** a quarantined item has no automatic path back. Only `RuntimeRecovery` (`member.points.expiry`, `member.cycle.assessment`) clears `quarantined_at`.

## 5. Residual limitations

- The segment, journey, delivery and catalog-job lanes keep their Phase 3 model: per-item attempts plus a business deadline, with an uncounted transient deferral. The "segment start: 3 candidates per visit" mitigation stays. These lanes already had per-item state, so L1 did not apply to them.
- If the earliest due member of a tenant is in backoff or quarantined, the cycles discovery still reports that tenant (see 03 §4). The visit then skips the blocked members by primary key. The cost is proportional to that tenant's blocked members, which are expected to be few and are visible as `commerce.lanes.backlog.quarantined{lane=cycles}`.
