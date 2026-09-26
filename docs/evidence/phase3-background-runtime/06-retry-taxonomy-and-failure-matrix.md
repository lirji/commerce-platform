# P3.5 — Failure Classification, Retry Budget and Quarantine

## 1. Taxonomy (`platform-runtime/.../FailureClass.java`)

Classification uses **only structured evidence**. Exception messages are never matched (tested with a message that says "数据库连接超时" on an `IllegalStateException`: result `UNKNOWN`). The cause chain is walked from the outside in, and the first recognisable type wins. Cyclic cause chains are handled.

| Class | Structured evidence | Transient? |
|---|---|---|
| `DEPENDENCY_UNAVAILABLE` | `DomainException(UNAVAILABLE)`, Spring `DataAccessResourceFailureException` (incl. `CannotGetJdbcConnectionException`), `TransientDataAccessResourceException`, `RecoverableDataAccessException`, `CannotCreateTransactionException`, JDBC `SQLTransientConnectionException` / `SQLNonTransientConnectionException` / `SQLRecoverableException`, SQLState class `08`, `java.io.IOException` | yes |
| `CONCURRENCY_RETRYABLE` | Spring `PessimisticLockingFailureException` / `ConcurrencyFailureException` (lock wait timeout, deadlock), JDBC `SQLTransactionRollbackException`, SQLState class `40` | yes |
| `TRANSIENT` | Spring `QueryTimeoutException`, `TransactionTimedOutException`, JDBC `SQLTimeoutException` / other `SQLTransientException`, `TimeoutException` | yes |
| `BUSINESS_REJECTED` | `DomainException` with any code other than `UNAVAILABLE` (`CONFLICT`, `NOT_FOUND`, `ILLEGAL_TRANSITION`, …) | no |
| `DATA_CORRUPTION` | Jackson `JacksonException` (payload/snapshot decode), JDBC `SQLDataException` | no |
| `CONFIGURATION_ERROR` | Spring `BadSqlGrammarException`, JDBC `SQLSyntaxErrorException`, SQLState class `42` (missing column or table, i.e. migration not applied) | no |
| `PERMANENT` | Spring `DataIntegrityViolationException`, JDBC `SQLIntegrityConstraintViolationException` | no |
| `UNKNOWN` | anything else (NPE, `IllegalStateException`, …) | no |

Notes:
- `DomainException(UNAVAILABLE)` is also thrown by sandbox channels when they are not configured. A missing configuration therefore looks like an unavailable dependency. It gets the long transient budget and the lane breaker (see §3). It is never spun rapidly, and it surfaces as `*_DEPENDENCY_UNAVAILABLE`. Splitting "not configured" from "down" would need a new domain code; that would be an API change and was not needed.
- `DATA_CORRUPTION` stays on the bounded poison budget rather than quarantining at once. During a rolling deploy an older instance may fail to decode a newer payload, and a few retries let the newer instance deliver it.

## 2. Retry budgets (`RetryPolicy`)

| Policy | Applies to | Base | Max | Jitter | Budget | Terminal condition |
|---|---|---|---|---|---|---|
| `POISON` | non-transient classes | 2 s | 16 s | +0–25% | 5 failures | event `ISOLATED`; order expiry stops (`expiry_attempts=5`) |
| `TRANSIENT` | transient classes | 2 s, doubling | 5 min | +0–20% | 300 failures (≈ 27 h, tested ≥ 24 h) | same terminal states, with `failure_class` showing the dependency class |
| deferral (no counter column) | segments, journeys, deliveries, catalog jobs | fixed ≈ 32 s | – | +0–20% | ends at the item's own business deadline (`valid_until`, `deadline`, `EXPIRED`) | business deadline |
| payment/refund checks (existing) | non-transient check failures | 4 s | 64 s | +0–1 s | 5 checks | "auto checks exhausted" (manual reconcile; `order.closing` re-arms payments) |

- The jitter source is injected (`delayMillis(failures, random)`), so tests are deterministic (`FailureSemanticsTest`).
- Synchronized recovery after an outage is spread by up to 20% of the delay.
- **Separate counters, only where justified:**
  - `platform_event.attempts` (poison) vs `transient_attempts`.
  - `order_record.expiry_attempts` vs `expiry_transient_attempts`.
  - `payment_attempt`/`payment_refund.check_attempts` vs `check_transient_failures`.
  - Item lanes that already have a business deadline keep one counter and do not count transient failures.
- **Event-level rule when consumers disagree:** if any consumer fails non-transiently, the event is charged to the poison budget. It is not masked by a sibling's transient failure (`mixedFailureCountsTowardThePoisonBudget`).

## 3. Lane dependency breaker (`TenantRotation`)

- Opens after **3 consecutive transient failures**. Because a transient failure ends that tenant's visit, the 3 failures usually come from 3 different tenant visits.
- Cool-down: 5 s, doubling up to **1 min**. The live outage test showed that a 5-min cap would keep lanes paused for minutes after the DB recovered, so the cap was lowered. After recovery, every lane resumes within ≤ 60 s. During an outage it costs 1 probe per lane per minute.
- While open, the lane takes no work and charges no item.
- After the cool-down, the first transient failure re-opens it (half-open). The first success closes it.
- A discovery-query failure (for example the database is down) counts toward the breaker, so the lane stops querying every second.
- Transient failures interleaved with successes (one tenant's lock contention) do not open it (tested).
- Non-transient failures never open it. A poison tenant is not a dependency outage.

## 4. Quarantine semantics

**Quarantine means that automatic processing was stopped because further attempts are not justified.** It is reached only by:
- 5 non-transient failures, or
- 300 transient failures (≈ 27 h of continuous dependency failure).

It is never reached by "we retried while MySQL was down for a minute" (tested: 10 connection-pool failures leave `attempts=0`).

| Evidence required | Event (`platform_event`) | Order expiry (`order_record`) | Payment/refund check |
|---|---|---|---|
| work id / tenant / type | `event_id`, `tenant_id`, `event_type` | `order_id`, `tenant_id` | `payment_id`/`refund_id`, `tenant_id` |
| consumer / handler | `last_error` = `consumer:ExceptionType[/DomainCode]` | lane `orders` | lane `payments`/`refunds` |
| failure class | `failure_class` | `expiry_error` = `CLASS:ExceptionType[/Code]` | `check_error` = `CLASS:ExceptionType[/Code]` |
| attempt counts | `attempts`, `transient_attempts`, `manual_retries` | `expiry_attempts`, `expiry_transient_attempts` | `check_attempts`, `check_transient_failures` |
| first / last failure time | `first_failed_at`, `last_failed_at` | `expiry_retry_at` (next attempt) | `next_check_at` |

- No payloads, exception messages, credentials or personal data are stored. The evidence is at most 160 characters.
- Order and payment rows keep only the last failure. Their first-failure time is not stored; this is a documented limitation.

## 5. Operator retry

| Item | Command | What is reset | What is preserved | Guard |
|---|---|---|---|---|
| Event | `POST /v1/admin/events/{id}/retry` (audited `Commands`, idempotency key) | `status→PENDING`, `attempts=0`, `transient_attempts=0`, `available_at=now`, `manual_retries+1` | `last_error`, `failure_class`, `first_failed_at`, `last_failed_at`, `skip_reason`, inbox rows | only `ISOLATED` or `SKIPPED`; refused when the type has no consumer now; own tenant only |
| Order expiry | `POST /v1/admin/orders/{id}/expiry/retry` (audited) | `expiry_attempts=0`, `expiry_transient_attempts=0`, `expiry_retry_at=NULL` | `expiry_error` | only when stopped and still unpaid; own tenant only |
| Payment check | existing `POST /v1/admin/orders/{id}/payment/reconcile` (manual, synchronous) | – | – | unchanged |

- Retry runs only unfinished consumers. The inbox is the dedup key, so a consumer that already committed is skipped (tested after partial consumer success and after quarantine).
- Tested cases:

| Retry after | Test |
|---|---|
| transient dependency outage | `temporaryOutageNeverQuarantinesHealthyEvents` (automatic, no operator needed) |
| business rejection | `businessRejectionQuarantinesWithCompleteEvidenceAndRetryRunsOnlyUnfinishedConsumers` |
| legacy decode failure | Phase 2 `malformedLegacyPayloadIsQuarantined` plus legacy quote decode |
| partial consumer success | same business-rejection test (`c0` ran once, `c1` re-ran) |
| quarantine | same test, plus `poisonOrderOnlyBlocksItselfAndIsQuarantinedWithEvidence` |
| skipped (no consumer) | `skippedEventCanBeReplayedOnceAConsumerExists`; refused while no consumer exists |

## 6. Failure matrix

| Failure | Where | Classification | Behaviour | Test |
|---|---|---|---|---|
| DB pool exhausted / connection refused during a consumer | events | DEPENDENCY_UNAVAILABLE | transient budget; the tenant visit ends; breaker after 3 | `EventFailureSemanticsTest` ×2 |
| DB down during discovery | any lane | DEPENDENCY_UNAVAILABLE | the run aborts and is logged; breaker after 3 runs, cool-down 5 s → 1 min | `TenantRotationTest.discoveryFailureTripsTheBreakerAndPropagates` |
| lock wait timeout / deadlock | any | CONCURRENCY_RETRYABLE | transient; no poison charge | `FailureSemanticsTest`, `TenantRotationTest.transientFailuresInterleaved…` |
| payment channel unavailable | payments/refunds | DEPENDENCY_UNAVAILABLE | the claimed check is refunded; transient backoff; breaker | `PaymentCheckLaneTest` ×2 |
| channel evidence conflict | payments | BUSINESS_REJECTED | consumes a check; stops at 5; counted in `quarantined` | `PaymentCheckLaneTest.conflictingEvidenceStillConsumesChecks` |
| inconsistent inventory hold on expiry | orders | BUSINESS_REJECTED | per-order backoff; stops at 5; other orders proceed | `OrderExpiryLaneTest.poisonOrder…` |
| consumer business rejection | events | BUSINESS_REJECTED | 5 → ISOLATED with full evidence | `EventFailureSemanticsTest` |
| stored payload missing a primitive | events | DATA_CORRUPTION | 5 → ISOLATED | Phase 2 test |
| missing column (migration not applied) | any | CONFIGURATION_ERROR | poison budget (bounded, visible) | `FailureSemanticsTest` |
| unexpected exception in a tenant visit | any lane | per type (UNKNOWN) | only that tenant's visit is lost | `TenantRotationTest.visitExceptionIsIsolatedToThatTenant` |
| exception escaping a whole lane run | scheduler | logged with class | only that lane run is lost; the other lanes are on other threads | `EventWorkerLaneTest.failingLaneDoesNotSkipOtherLanes` |
| type with no consumer | events | – (B1) | declared → SKIPPED at write; undeclared → PENDING + `EVENT_NO_REQUIRED_CONSUMER` | `EventFailureSemanticsTest.noConsumerEventsHaveExplicitSemantics` |
