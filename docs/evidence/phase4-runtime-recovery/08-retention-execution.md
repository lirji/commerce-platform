# P4.6 — Retention Execution

## 1. Configuration (externalized; names follow the `commerce.*` convention)

| Property | Default | Floor | Meaning |
|---|---|---|---|
| `commerce.retention.enabled` | `false` | – | master switch |
| `commerce.retention.delivered-events` | unset (not purged) | 7 d | DELIVERED events and their inbox rows |
| `commerce.retention.skipped-events` | unset | 7 d | SKIPPED events and their inbox rows |
| `commerce.retention.commands` | unset | 30 d | completed idempotent command records |
| `commerce.retention.live-yield` | 200 | ≥ 1 | yield when this many routed live events are due |

- Values accept ISO-8601 (`P30D`) or days (`30d`).
- If retention is enabled without any class, or with a value below its floor, **the application fails at startup**.

## 2. Execution model (`RetentionLane`)

| Requirement | Implementation |
|---|---|
| bounded background workload | 12th lane `retention` on the shared scheduler, fixed delay **5 s** |
| batch size | 500 rows per transaction |
| time budget | 300 ms per run (checked between batches); at most 2,000 main rows per run |
| query index | DELIVERED/SKIPPED: `ix_event_delivery(status, available_at, event_id)`, the range at the old end; inbox: **new** `ix_inbox_event(event_id)` (V40; the PK is `(consumer, event)`); commands: **new** `ix_command_created(created_at)` (V40) |
| no huge locks | `READ COMMITTED` (no gap locks); `FOR UPDATE SKIP LOCKED` on the selected ids, so rows locked by delivery or by replay's `FOR SHARE` are skipped, never waited on; delete by primary key list |
| no unbounded DELETE | every statement is limited to one batch of ids, or `LIMIT 500` for commands |
| does not block live processing | yields the whole run while routed live events are due above the threshold; one DB connection per run (one lane thread) |
| progress visibility | `commerce.retention.purged{class}`, `commerce.retention.inbox.purged`, `commerce.retention.failures`, `commerce.retention.failures.consecutive`, `commerce.retention.lag{class}` (seconds beyond retention of the oldest eligible row; the value is cached for 5 s), `commerce.retention.enabled`, lane schedule metrics `commerce.lanes.start.lag{lane=retention}` and `run.duration`, and the platform view section `retention` |
| failure isolation | an exception ends only that run: it is classified, counted and logged with class and type only. Other lanes are on other tasks. Deletion is idempotent, so the next run continues from the oldest rows. |
| retry semantics | no per-row state is needed: an undeleted row stays eligible. `RETENTION_FAILURE` fires after 3 consecutive failed runs. |
| fail-safe | if the number of deleted events differs from the number locked, the batch **rolls back** (`IllegalStateException`) |

## 3. Safety invariants (R12) and their tests (`RetentionTest`, 7 tests)

| Invariant | Mechanism | Test |
|---|---|---|
| never delete unfinished work | select and delete both filter `status IN ('DELIVERED','SKIPPED')` | `onlyProvablySafeDataIsPurged`: an old PENDING event and its inbox row are kept |
| never delete dedup protection that is still needed | inbox rows are only deleted in the same transaction as their terminal event | same: old ISOLATED and PENDING inbox rows are kept; DELIVERED inbox rows are removed with their events |
| never delete replay-safety data | cutoff = min(retention cutoff, earliest `from_at` of RUNNING/PAUSED replay jobs); replay locks the row `FOR SHARE` per item | `activeReplayRangesAreRetained`; `lockedRowsAreSkippedNotWaitedOn` (a shared lock gives no wait (< 5 s) and no delete; deleted after release) |
| never delete audit | `platform_audit` and `platform_recovery` are not referenced by the mechanism | `onlyProvablySafeDataIsPurged`: the audit row is kept |
| never delete an in-flight command | `response_json IS NOT NULL` | same: the old command without a response is kept |
| active quarantine investigation | ISOLATED is never selected | same |
| referential integrity | there is no FK between inbox and event; deleting the inbox first then the event, in one transaction, leaves no orphan in either direction | same (inbox count 0 after) |
| bounded | `MAX_ROWS` and the time budget | `cleanupIsBoundedPerRun`: 2,600 eligible rows, first run ≤ 2,000, the rest in later runs |
| no monopolizing (R14) | yield, batches, `READ COMMITTED`, `SKIP LOCKED`, 5 s cadence | `cleanupYieldsToLiveWork` |
| disabled by default, no dangerous values | Policy validation | `retentionIsDisabledByDefaultAndRefusesDangerousConfiguration` |
| failures are visible | counters and alert codes | `failuresAndLagProduceFixedAlertCodes` (a mocked outage gives 3 consecutive failures and `RETENTION_FAILURE`; lag over 1 day gives `RETENTION_LAG_HIGH`; a healthy state gives no alert) |

The tests use a fixed 1991 clock and 1990 fixtures, so they can never delete data of other tests in the shared schema.
