# P4.5 — Replay Execution and Verification

## 1. Execution model (`EventReplay`)

| Concern | Mechanism |
|---|---|
| scope | own tenant only; one consumer; 1–10 event types, all handled by that consumer; `[from, to)` ≤ 31 days and not in the future; `maxEvents` ≤ 10,000 (default 10,000); at most 3 RUNNING or PAUSED jobs per tenant |
| dry run | `POST /v1/admin/runtime/replay/dry-run`. Read only. It returns the gate decision, the count per type (capped at `maxEvents+1`), the number already processed by the consumer, `wouldExecute` and `capped`. It answers "how many / which types / which side-effect classes / which are safe or blocked / estimated scope". Tenants: always the caller's own. |
| authorization | dry run, list and get need `RUNTIME_RECOVERY_READ`; create and control need `RUNTIME_REPLAY_EXECUTE`. Both are tenant ADMIN only (see 11). |
| audit | `REPLAY_CREATE`, `REPLAY_PAUSE`, `REPLAY_RESUME` and `REPLAY_CANCEL` rows in `platform_recovery` (work type `event.replay`), plus the job row itself with its counters and reason |
| execution | a separate lane `replay` (`TenantRotation`: quantum 20, 50 tenants per discovery, 200 ms or 100 items per run) on the shared scheduler pool |
| per item | one transaction: lock the job row (`FOR UPDATE`), re-check the status and the **gate**, find the next event after the cursor (consistent read with no gap locks), lock that event by primary key `FOR SHARE` (excludes retention's `SKIP LOCKED` delete), insert the inbox row (`UPDATE`/`INSERT IGNORE` semantics), run the consumer, advance the cursor and counters with a version check. The effect and the progress commit together. |
| live priority | before each run, count due PENDING events of routed types (bounded by `commerce.replay.live-yield`, default 200). At or above the threshold the whole replay run is skipped (`commerce.replay.yielded`). Live events always use their own lane. |
| failure | non-transient consumer failure: the item rolls back; a separate transaction records `failed+1` and `last_error=<event>:<class>:<type>` and skips that event. At `MAX_FAILURES=10` the job becomes `FAILED`. Transient failure: nothing is skipped or counted, the visit ends, and the lane breaker applies. |
| pause / resume / cancel | `POST /replays/{id}/control` with `expectedVersion` and a reason. Resume re-checks the gate. Cancelled and completed jobs cannot be controlled. |
| observability | `commerce.replay.executed`, `commerce.replay.already.processed`, `commerce.replay.failures`, `commerce.replay.blocked` and `commerce.replay.yielded`; `commerce.lanes.*{lane=replay}` (backlog = running jobs, quarantined = failed jobs); alert `REPLAY_BLOCKED` |

Transaction boundaries (§42):
- No lock is held across the scan: `next` is a non-locking read.
- No lock is held across operator interaction: dry run and create are separate requests, and control is its own short command.
- Each item locks one job row and one event row for the duration of one consumer transaction (10 s timeout).

## 2. Idempotency boundary per path

| Path | Boundary |
|---|---|
| create | `platform_command` key plus `(tenant, job_id)` primary key |
| control | command key plus the job `version` compare-and-set |
| UNPROCESSED item | inbox PK `(consumer_id, event_id)`. `INSERT IGNORE` returning 0 means "already processed", and the consumer is not called |
| REPROCESS item | only a PURE consumer, whose effect is a convergent upsert from authoritative data. The inbox row is ensured first. |
| progress | job row lock plus `version` CAS: two instances cannot both examine the same cursor position |

## 3. Verification (`ReplayTest`, 10 tests, all pass)

| Test | Proves |
|---|---|
| `everyConsumerIsClassifiedAndOnlyThePureProjectionIsReplayable` | 10 of 10 consumers classified with evidence; only `marketing-effects-v1` is allowed |
| `safetyGateFailsClosed` | unclassified is rejected; FINANCIAL, EXTERNAL and IRREVERSIBLE are rejected even when declared replayable; IDEMPOTENT_WRITE cannot REPROCESS; PURE without the `reprocess` flag cannot REPROCESS; an unknown consumer is rejected |
| `sensitiveConsumersAreRejectedBeforeAnyWork` | dry run for `order-payment-v1` gives `REPLAY_NOT_SUPPORTED` with `wouldExecute=0`; create for `order-payment-v1`, `payment-order-closing-v1`, `member-growth-v1` and `journey-order-paid-v1` → 409, no job row, `blocked` +4 |
| `unprocessedReplayRestoresTheProjectionExactlyOnce` | **side-effect verification**: 3 real orders through the HTTP API and the event lane. The projection and inbox rows of 2 orders are deleted to simulate "consumer never processed". The dry run (3 events / 1 processed / 2 would execute) changes nothing. UNPROCESSED gives examined 3, executed 2, already processed 1; the projection rows are restored; each event has exactly 1 inbox row; the event status stays DELIVERED; no other consumer's inbox changes. REPROCESS executes 3 with an **identical projection** (converges). A third UNPROCESSED executes 0. |
| `jobsArePausableResumableAndCancelable` | a paused job does not progress; a stale version gets 409; resume works; cancel stops progress; a terminal job cannot be resumed; the audit chain is CREATE → PAUSE → RESUME → CANCEL |
| `scopeLimitsAreEnforced` | a range over 31 days, a future end, a type the consumer does not handle, `maxEvents` over 10,000 and an unknown consumer are all rejected; the 4th active job is rejected |
| `replayYieldsToLiveWork` | with a due live event of a routed type and threshold 1, the run yields, `yielded=1` and there is no progress (R13) |
| `executionTimeGateStopsARunningJobWhenTheClassificationChanges` | a running job whose consumer is now FINANCIAL becomes `FAILED` with `GATE:REPLAY_NOT_SUPPORTED`, and the consumer is never called |
| `consumerFailuresAreCountedSkippedAndBounded` | 12 events with one poisoned: failed 1, the other 11 run, and the job completes; the failed event's inbox row was rolled back. An always-failing consumer makes the job `FAILED` after exactly 10 |
| `twoInstancesAdvanceOneJobWithoutDuplicateExecution` | two instances tick concurrently: 12 events are examined 12 times and executed 12 times, each exactly once (R11) |

## 4. Replay vs live work (R13)

- Replay runs on its own lane with a budget of 200 ms or 100 items per run and a 1 s fixed delay. It therefore uses at most about 17% of one scheduler thread, even with an unbounded job.
- It yields the whole run when routed live events are due above the threshold.
- It never takes events from the live `PENDING` set (it reads only `DELIVERED`), so it cannot delay live delivery through contention on the same rows.
