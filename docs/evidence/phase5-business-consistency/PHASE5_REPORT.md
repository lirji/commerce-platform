# Phase 5 business consistency report

**Phase:** Transaction, Payment, Fulfillment & Benefit Consistency Hardening

**Final Status:** `PHASE_5_COMPLETE_WITH_LIMITATIONS`

## Executive Summary

The current sandbox-based commerce flow has executable evidence for the applicable payment, expiry, event, fulfillment, benefit, points, coupon, after-sales and refund invariants. Thirteen new MySQL integration tests cover contested transitions, a second live application context, and a failure between consumer effects and commit. Three controlled mutations each caused its targeted business test to fail, then were byte-restored. No production Java, SQL migration or Phase 2–4 runtime implementation was changed. The observed intermittent `/aftersales` 409 was a browser readiness race; the valid backend precondition remains enforced. Real provider callbacks and third-party WMS operations are outside this repository's current adapters and require their own acceptance evidence before activation.

## Starting Baseline

On the clean task branch, `./scripts/verify.sh` passed before Phase 5 edits: `commerce-app` 251 run / 0 failed / 5 opt-in skips; architecture tests 3/3. Phase 4's 329-run result predates a later package refactor and is preserved as historical evidence, not treated as the current baseline. See `00-baseline.md`.

## Phase 4 Closure Gate

Phase 4 replay, recovery, fairness and retention behavior was read and frozen. The existing financial and irreversible replay gate remains fail closed; `ReplayTest`, `MultiInstanceRecoveryTest`, `CrashRecoveryTest`, event lane and runtime tests ran in the clean build. The historical Phase 4 report and this phase's `01-phase4-closure.md` identify the boundary.

## Intermittent /aftersales 409 Decision

**`TEST_RACE_ONLY` for the observed browser case.** Payment reconciliation commits a payment fact and event before its asynchronous order and fulfillment consumers commit. The old browser helper interpreted a zero manual pump count as business completion even when a background worker held the event. The spec now waits for that order's fulfillment record while pumping. The 409 was the correct response to an order still in `PAYMENT_IN_PROGRESS`; no invalid aftersale was committed. The revised browser journey passed. See `01-phase4-closure.md`.

## Consistency Architecture

MySQL owns the current business state. Commands, audit and business writes share use-case transactions. Required events are inserted through the existing Outbox in the same transaction; per-consumer Inbox rows and effects share the consumer transaction. The decision is **KEEP_CURRENT_DESIGN**, with no second messaging framework. The flow map is `02-consistency-map.md`; the required invariant and transaction tables are `05-consistency-matrix.md` and `04-transaction-matrix.md`.

## Business Invariant Catalog

`03-business-invariants.md` records I1–I15 with a guard, executable test and scope. The corresponding brief B1–B18 are covered for installed components by payment and expiry tests, event redelivery/crash tests, guarded fulfillment and grants, capacity and refund tests, two-instance tests, existing replay safety tests and the final clean runtime suite. Callback-specific B4/B11 and actual external-effect portions of B12/B16 are **not claimed for uninstalled adapters**; the common terminalization path is exercised by simultaneous rechecks. B17/B18 rely on the current replay gate and the clean Phase 2–4 regression tests. The partial benefit bundle semantics remain a bounded product decision.

## Order State Model

The legal order path is `PENDING_PAYMENT → PAYMENT_IN_PROGRESS → PAID → FULFILLING → COMPLETED`; no transition leaves `COMPLETED` or `CANCELLED`. An unresolved started payment reaches `CLOSING`, preserving reservations until channel evidence establishes paid or closed. The domain lifecycle plus expected-version update guard transitions. `02-consistency-map.md` shows the state graph and implementation locations.

## Payment Consistency

Payment terminalization locks and versions the attempt, validates the channel fact, binds a provider transaction uniquely and appends the paid/closed event atomically. Concurrent trusted rechecks create one paid fact and one event; one provider transaction cannot pay two orders. `PersistedCommerceTest` contains both tests. Provider authenticity/signature is not tested because no callback adapter exists.

## Payment / Expiry Concurrency

Expiry selects only unpaid candidate states, rechecks under the order lock, and cannot turn a paid row into cancellation. If a payment is unresolved it enters `CLOSING`; a later paid fact may legally converge to `PAID`. Tests cover stale discovery, payment before lock, close/payment overlap, paid proof delayed past expiry, and two live application instances. The exact E1–E5 ledger is `14-concurrency-tests.md`.

## Payment Reconciliation

The payment check lane and explicit recheck use the stable payment ID and observe the sandbox ledger before terminalization. A lost client response remains a durable attempt; the existing background reconciliation test recovers the paid fact. Real provider query and bounded retry behavior must be checked with the provider adapter before use. See `13-compensation-reconciliation.md`.

## Event / Transaction Atomicity

`Outbox.append` requires a surrounding transaction. The payment-event removal mutation caused the corresponding one-event assertion to fail. A failure after actual order/stock consumer effects but before commit rolled back both effects and the Inbox row, then normal dispatch applied the event once. See `16-failure-injection.md`.

## Consumer Idempotency

The Inbox primary key is `(consumer_id,event_id)` and successful consumer effects commit with that row. Redelivery tests cover payment/order settlement, fulfillment/after-sales, entitlement grant and stock. The injected precommit failure was retried once and a later duplicate did not resell stock.

## Fulfillment Consistency

One row per tenant/order, status/version guards and the after-sales fulfillment hold constrain shipping. Concurrent duplicate shipment commands create one transition; a shipment/after-sales race resolves to a legal combination. This proves the installed local WMS sandbox behavior, not a third-party shipment effect.

## Benefit Consistency

Entitlement reservations use quota guards; grant requests, versions, source keys and Inbox dedup protect grant effects. Two orders racing for the last unit yield one reservation. Redelivered grants remain one grant. A consumed entitlement refunded later records compensation debt instead of silently declaring reversal complete.

## Points / Coupon Consistency

Points account, lots and ledger commit together with source/order/refund keys; existing original-allocation refund tests passed. Coupon issue quota and source uniqueness, conditional hold/use status and expiry checks are exercised. The added coupon reservation/revocation race has one winner. The current full-return coupon restore rule is tested; changing that policy would be a product decision.

## Partial Benefit Decision Analysis

`03-business-invariants.md` compares atomic bundle, independent partial grant and compensating bundle, including retry, customer visibility and operator cost. The current implementation has independently keyed grants and no product-level all-or-nothing bundle promise. The bundle policy is **deferred**, with existing per-grant correctness retained.

## After-Sales Consistency

Opening a case locks/holds fulfillment and permits one active case per order. Competing submissions yield one case and a conflict; shipment against the hold cannot both succeed. The browser wait change only waits for the paid fulfillment precondition and does not loosen this rule.

## Refund Consistency

Refund reservation is a conditional cumulative update bounded by the paid amount; an `UNKNOWN` attempt continues to occupy capacity. Unique case and provider transaction keys, row locks and state guards prevent duplicate requests and success events. Concurrent requests, concurrent rechecks, partial returns and mismatch evidence are tested. Omitting the cumulative reservation made the refund mutation test fail.

## Compensation Model

The model distinguishes database rollback from an external effect. Current installed internal effects either retry atomically or leave explicit `UNKNOWN`, `REFUNDING`, `REQUESTED` or compensation debt state. Consumed benefits require an operator decision. No rollback is claimed to reverse an external provider or shipment. See `13-compensation-reconciliation.md`.

## Reconciliation Model

Payment and refund recheck use stable IDs and durable sandbox facts. An unknown result is queried before any unsafe second financial attempt. Pending events retry through the existing dispatcher; isolated financial effects use governed recovery and do not enter generic replay. The real-adapter contract remains a future gate.

## Concurrency / Locking Model

Tenant-scoped unique keys, row locks, expected-version transitions and conditional quota/refund updates serialize contested writes. The order of payment, order, fulfillment and after-sales state changes is captured in `02-consistency-map.md`; test cases C1–C15 and E1–E5 are in `14-concurrency-tests.md`. No cache is treated as financial or inventory authority.

## Multi-Instance Results

`twoLiveAppInstancesConvergeWhenExpiryRacesWithPaidRecheck` uses a second Spring context, separate connection pool and transaction manager against the same isolated MySQL schema. It converges to the same paid fact. Existing `OrderExpiryLaneTest#concurrentInstancesExpireEachOrderExactlyOnce` and `MultiInstanceRecoveryTest` also pass. This is multi-instance local database evidence; it is not a production cluster or real provider test.

## Failure Injection Results

The injected consumer failure occurred after real handler effects and before commit; the transaction removed the effects and Inbox together. Normal dispatch then applied once, and redelivery made no second effect. Historical Phase 4 crash/restart tests also passed. This injection is a transaction-level crash window, not a process kill; that limit is recorded in `16-failure-injection.md`.

## Database Constraints / Indexes

The existing migrations provide tenant/order payment uniqueness, provider transaction uniqueness, tenant/order fulfillment primary key, refund case/provider transaction uniqueness, `refund_reserved` bounds, event/Inbox keys and expiry indexes. Phase 5 found no demonstrated missing constraint or query plan requiring a migration. The refund capacity guard was deliberately removed in a mutation and its test failed. No historical migration was rewritten.

## Observability

Existing payment/refund attempts, pending/isolated events, Inbox, command, audit and recovery rows retain the uncertainty and retry evidence. The after-sales 409 retains the existing conflict code. No new metric or alert was justified by a new production failure mode. See `17-observability.md`.

## Security / Authorization

Existing authenticated admin/member endpoints and tenant-scoped writes remain in use; no authorization logic changed. No provider signature or external callback validation is claimed. No credentials are stored in the evidence; generated log secrets are redacted by the browser evidence script.

## Performance Impact

Production source and schema are unchanged, so Phase 5 introduces no product throughput change. The added tests consume build time only. Phase 4 local capacity measurements are historical and not treated as a new guarantee.

## Tests

Final `./scripts/build.sh` passed after mutation restoration: frontend `npm ci` and build/typecheck, `mvn -Pwith-ui clean verify`, and jar UI check. `commerce-app`: **264 run, 0 failures, 0 errors, 5 optional skips**; architecture: **3 run, 0 failures**. The 13-test increase comprises 12 persisted-commerce tests and one coupon concurrency test. The baseline and clean gate are in `00-baseline.md` and `18-regression.md`; the full clean log is local at `/tmp/phase5-clean-closure.log`.

## Mutation / Negative Tests

Three single-guard mutations all compiled and were detected by one targeted business assertion each: paid included in expiry candidates, missing payment terminal event, and missing cumulative refund reservation. Original source bytes and module artifacts were restored before the final clean build; the packaged jar contains no mutation or backup path. Details and failure messages are in `16-failure-injection.md`.

## Browser Regression

Fresh-tenant browser run against the clean jar: **20 passed / 4 failed of 24**. The four failures in catalog merchandising, coupon deliveries, member behavior and member cycles are `KNOWN_EXISTING`, matching Phase 4 failing assertions. No new deterministic browser regression was seen. The operations journey, including `/aftersales`, passed; the intermittent 409 is `RESOLVED` as a test-readiness race for this run. The result does not claim the old four failures are fixed. See `18-regression.md` and the reviewable `e2e/final-r1-summary.md`.

## Known Limitations

No live payment/refund callback endpoint or third-party WMS adapter exists. Consequently signature verification, out-of-order callback handling and external side-effect reconciliation are not certified. The browser suite retains four previously known failures. Old isolated test-schema fixtures remain, but Phase 5 test and browser tenants use fresh identifiers and do not rely on cleanup. A transaction-level failure injection does not replace a process-kill experiment.

## Blocked / Deferred Product Decisions

Partial benefit bundle semantics remain open; the current independently keyed grant behavior is explicit. Late-paid already-cancelled orders are disallowed by the current state machine; changing customer treatment would require policy. A real provider/WMS rollout also needs external authenticity, idempotency and query semantics. No decision was guessed for a new financial replay policy: generic replay remains blocked.

## Changed Files

- `commerce-app/src/test/java/com/lrj/commerce/app/PersistedCommerceTest.java`: twelve integration cases.
- `commerce-app/src/test/java/com/lrj/commerce/app/CouponDeliveryTest.java`: coupon use/revoke race.
- `frontend/tests/operations.spec.ts`: wait for the order's fulfillment precondition.
- `docs/evidence/phase5-business-consistency/`: map, invariant/transaction matrices, tests, failure injection, browser evidence, scripts and this report.
- `docs/doc-map.md`: report index.

## Evidence Location

`docs/evidence/phase5-business-consistency/` contains the baseline, Phase 4 gate, flow map, I1–I15 catalog, required matrices, C1–C15/E1–E5 test ledger, compensation/reconciliation, mutation log, observability and regression record. `e2e/final-r1-summary.md` records the browser assertions. Raw browser JSON, logs and screenshots remain local and are ignored by existing Git rules, as are raw Maven logs and mutation backups; the Markdown evidence is reviewable without them.

## Commit Boundary Recommendation

This report was prepared before Git delivery; the user subsequently explicitly authorized commit and remote `main` push. The task branch is `fix/phase5-business-consistency`. Keep the two Java test files and browser readiness spec together as the behavior verification unit, followed by the Phase 5 evidence/doc-map unit. Review each staged diff and preserve unrelated work. A Git push is not production deployment authorization.

## Next Recommended Phase

Resolve the four existing browser/spec mismatches in their own task. Before any real provider or WMS activation, implement and verify callback authenticity, stable external request IDs, query-before-retry, ordering, failure recovery and multi-instance behavior against that provider's actual contract. Obtain the partial benefit bundle product policy before changing grant semantics.
