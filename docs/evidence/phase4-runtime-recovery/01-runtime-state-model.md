# P4.0 — Runtime State Model: Scheduling ≠ Retry ≠ Recovery ≠ Replay ≠ Retention

## 1. Definitions (used consistently in code, API and documents)

| Concern | Definition | Who triggers it | Where it lives |
|---|---|---|---|
| **Scheduling** | When a work item is *due* by business rules: an event's `available_at`, a lot's `expires_at`, a member's `cycle_due_at`, an order's `expires_at`. | the business, over time | domain columns, `TenantRotation` (fairness only) |
| **Retry** | Automatic continuation of the *same* logical execution after a recoverable failure, with a bounded, classified budget (`RetryPolicy.POISON`: 5 non-transient failures; `RetryPolicy.TRANSIENT`: 300 transient failures ≈ 27 h). | the runtime | per-work retry columns, or `member_work_retry` (new) |
| **Quarantine** | Retry stopped because further automatic attempts are not justified (budget exhausted). This is a terminal state for automation. | the runtime | `ISOLATED`, `quarantined_at`, `expiry_attempts ≥ 5`, … |
| **Recovery** | An operator action that moves *stopped* work back to an executable state (`RETRY`) or terminates it deliberately (`SKIP`). It is audited and idempotent, keeps the failure evidence, and only runs what has not completed. | a tenant admin | `RuntimeRecovery`, `RecoverableWork`, `platform_recovery` (new) |
| **Replay** | A deliberate request to process *historical, already delivered* events again for one consumer. It is only allowed when the replay safety gate can prove it safe. | a tenant admin | `EventReplay`, `ReplayGate`, `platform_replay` (new) |
| **Retention** | The lifecycle end of data that is provably no longer needed. Bounded, fail-safe deletion. | the runtime, when configured | `RetentionLane` |

These are separate mechanisms with separate state. They are not phases of one state machine:
- A retry never produces an audit row.
- A recovery never changes the scheduling time of a business item (only the retry state).
- A replay never changes the status of an event.
- Retention never touches non-terminal rows.

## 2. States per work type (existing states reused; only the missing ones added)

The brief's illustrative states map onto existing columns. Nothing was renamed.

| Illustrative state | events (`platform_event`) | points expiry (`member_point_lot` + `member_work_retry`) | cycle assessment (`member_record.cycle_due_at` + `member_work_retry`) | order expiry (`order_record`) |
|---|---|---|---|---|
| READY | `PENDING`, `available_at ≤ now` | lot due and no retry row, or `retry_at ≤ now` | `cycle_due_at ≤ now` and no retry row, or `retry_at ≤ now` | due, attempts below budget, `expiry_retry_at` null or past |
| PROCESSING | row lock held inside the consumer transaction; not persisted | member lock held inside the item transaction | same | `SKIP LOCKED` claim inside the order transaction |
| SUCCEEDED | `DELIVERED`, with one inbox row per consumer | lot `remaining=0`, ledger `EXPIRE`, retry row deleted | `cycle_due_at` = next boundary, retry row deleted | `CANCELLED`/`CLOSING` |
| RETRY_WAIT | `PENDING`, `available_at > now`, attempts > 0 | retry row with `retry_at > now` | retry row with `retry_at > now` | `expiry_retry_at > now` |
| QUARANTINED | `ISOLATED` | `quarantined_at` set | `quarantined_at` set | `expiry_attempts ≥ 5` or transient ≥ 300 |
| SKIPPED | `SKIPPED` (`NO_REGISTERED_CONSUMER` or **new** `OPERATOR_SKIPPED`) | – (not supported: skipping would leave the lot with a non-zero `remaining` and no `EXPIRE` ledger entry forever; the wallet already hides expired lots, so the only effect of "skip" would be a permanent ledger inconsistency) | – (not supported) | – (not supported: an unpaid order must not keep inventory forever) |
| RECOVERY_REQUESTED / RECOVERING | not persisted: recovery is one synchronous command transaction | same | same | same |
| RECOVERY_FAILED | the recovery command returns a per-item `REJECTED` result with a code, and an audit row is written | same | same | same |

**Why no persisted RECOVERY_REQUESTED/RECOVERING state:** a recovery is a single short, conditional update inside the idempotent command transaction (≤ 50 explicit items, 10 s timeout). No work is done asynchronously on behalf of a recovery request:
- the item is simply made READY again;
- the normal lane then retries it under the normal retry budget.

A crash during the command rolls it back entirely. Replaying the same idempotency key then returns the committed result or re-executes it. A persisted "requested" state would add a second place where work can get stuck without adding safety.

## 3. Distinguishing the six outcomes the brief requires

| Outcome | How it is distinguishable |
|---|---|
| normal retry | the retry counters grow; there is no audit row; `failure_class` and `last_error` are updated by the runtime |
| automatic recovery | a transient dependency outage clears by itself. The breaker closes on the first success, and items keep `attempts=0` (only `transient_attempts` grew) |
| manual/operator recovery | a `platform_recovery` row (actor, key, work, previous → new state, class, reason, result); `manual_retries`/`manual_recoveries` grows; counters are reset while the evidence is kept |
| replay | a `platform_replay` job plus `REPLAY_*` audit rows; the event status stays `DELIVERED`; one inbox row per consumer |
| terminal skip | `SKIPPED` with `skip_reason` (`NO_REGISTERED_CONSUMER` declared at write time, or `OPERATOR_SKIPPED` with an audit row) |
| terminal success | `DELIVERED` / business terminal state; retry row absent |
