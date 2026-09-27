# P4.7 — Multi-Instance Recovery and Replay (§19–20)

"Instances" are independent service objects or threads, each with its own connections and transactions, working on overlapping data. This is the same model that Phase 3 used for order expiry, where 4 instances × 400 orders each cancelled exactly once.

| Required property | Test | Result |
|---|---|---|
| no duplicate consumer success (replay) | `ReplayTest.twoInstancesAdvanceOneJobWithoutDuplicateExecution` | 2 instances, 12 events: examined 12, executed 12, each event executed **once** |
| no duplicate financial / points side effect | `MultiInstanceRecoveryTest.backgroundAndOperatorExpiryNeverDoubleExpireALot` | the lane plus 3 concurrent admin `expire` commands on 40 lots: `remaining=0`, **40** `EXPIRE` ledger rows, `SUM(expired)=400` |
| no lost recovery request | `concurrentRecoveriesOfOneItemApplyExactlyOnceAndAreAllAudited` | 8 concurrent commands with different keys → 1 APPLIED, 7 REJECTED, **8 audit rows**; also on the RESOLVED path |
| no corrupt retry counters | `concurrentFailureRecordingNeverLosesCounts` | 2 × 50 concurrent failure records → `transient_attempts=100`, `attempts=0` |
| no invalid terminal transition | `concurrentSkipAndRetryProduceOnlyValidTransitions` (10 rounds) | the applied audit rows form a chain in which each `previous_state` equals the prior `new_state`, starting at ISOLATED; the final DB status equals the last `new_state` |
| crash + restart across instances | `CrashRecoveryTest` (09) | – |
| Phase 3: event lane and order expiry across instances | `EventSchedulingFairnessTest.concurrentWorkersNeverProcessAConsumerTwice`, `OrderExpiryLaneTest.concurrentInstancesExpireEachOrderExactlyOnce` | pass (regression) |

## Defects found by these tests (fixed)

1. **RESOLVED double-apply.** When the underlying item no longer needed work, `ItemRetries.recover` deleted the retry row without a compare-and-set, so 2 of 8 concurrent recoveries reported `APPLIED`. Fixed with a locking read (`WorkRetryMapper.lock … FOR UPDATE`) plus an affected-row check. The test now passes.
2. **Stale audit `previous_state` for events.** `EventRecovery` read the state without a lock, so a RETRY that queued behind a SKIP would audit `ISOLATED` while the real previous state was `SKIPPED`. Fixed with `EventMapper.lockView … FOR UPDATE`, also used by the legacy retry endpoint.

## Claiming / lease decision (§20)

**No `claimed_by`, `lease_owner` or `lease_until` columns were added.** Every Phase 4 path holds its claim only inside one short transaction:
- recovery: row lock inside the command transaction;
- replay: job row lock plus event `FOR SHARE` per item;
- retention: `SKIP LOCKED` per batch;
- member items: member lock per item.

A crash releases every claim through the rollback (09). The tests above found no correctness gap that a lease would close; the two defects were fixed with row locks. The Phase 3 conclusion stands: **transaction-scoped locking is sufficient**, and distributed leases would add complexity without a demonstrated need.

Known multi-instance cost (unchanged from Phase 3):
- cursors and breakers are per instance, so N instances duplicate *discovery* queries (not work);
- the replay and retention lanes run on every instance, and their batches are serialized by row locks and `SKIP LOCKED`.
