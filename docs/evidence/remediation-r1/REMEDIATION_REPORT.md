# Remediation R1 — Async Backbone, Order Expiry, Authorization, Lock Order

- Date: 2026-09-25. Baseline: `main` @ `86d7e0a` + working tree. Source findings: `.project-analysis/project-architecture-business-gap-evolution-report.md` (read-only analysis of the same day). The earlier `.engineering/exploration/*` gaps (V1–V15) were already marked closed by that report and are treated as **STALE**.
- Scope rule: backend only. The uncommitted frontend work (`frontend/src/**`, `CODEX_PROGRESS.md`) was neither changed nor judged.
- Environment: Java 21, Maven 3.9, MySQL 8.4 (`dev-infra-mysql84-1`), isolated schema `commerce_test_20260923`. The 8602 container and `commerce_local` were not touched.
- Final state: **REMEDIATION_COMPLETE_WITH_LIMITATIONS**.

## Execution boundary

| Item | Value |
|---|---|
| CURRENT_BASELINE | 220 tests green (`verify-baseline.log`). |
| TARGET_BASELINE | The report's Phase 0 "Stabilize" items that can be proven in this repo without external providers. |
| IN_SCOPE | Consumer isolation, worker isolation, scheduled expiry, CLOSING recovery, deny-by-default HTTP authorization, lock ordering, app-shell boundary fitness. |
| OUT_OF_SCOPE | Items that need a provider or business decision (IdP, real payment/WMS, refund failure semantics, maker-checker rules). Also schema changes, frontend, metrics stack, and service extraction. |
| BUSINESS_INVARIANTS | Unknown payment never releases resources (CLOSING kept). Idempotent commands. Each consumer's effect happens at most once. Cent-exact money. |
| ARCHITECTURE_INVARIANTS | Cross-module calls only via `.api`. One MySQL transaction per use case. No broker, no new infrastructure. |
| DATA_INVARIANTS | No migration was added. `platform_inbox (consumer_id,event_id)` remains the dedup key. |
| API_COMPATIBILITY | All registered endpoints keep their role rules. One intentional change: unregistered `/v1/**` paths return **403** instead of 404 (see S4). |
| ROLLBACK_BOUNDARY | Every slice is code-only and revertible per file. No schema or data rollback is needed. Events already delivered stay valid under either dispatcher. |

## A. Remediation Summary

```text
Total Findings: 35  (19 architecture items + 11 capability gaps + 5 found during remediation)
Fixed:           6  (F01 F02 F03 F07 F12 N1)
Partially Fixed: 4  (F05 F15 F16 F18)
Deferred:       20  (NEXT/LATER, see F)
Invalidated:     0  (F12 promoted from INFERENCE to reproduced FACT)
Blocked:         2  (F08 IdP choice, G10 WMS provider)
Not recommended: 2  (F11 saga split, G11 settlement)
Out of scope:    1  (N5 e2e/UI drift)
```

## B. Change Matrix

| Finding | Validation | Change | Evidence | Tests | Status |
|---|---|---|---|---|---|
| F01 P0 all consumers of an event share one transaction | CONFIRMED. Reproduced: the healthy sibling's inbox row was rolled back and the sibling re-ran 5× | `EventDispatcher`: one transaction per consumer. Inbox dedup skips finished consumers on retry. The delivered mark commits with the last consumer. | before: `expected [test-isolation-healthy] but was []`, `expected 1 but was 5` | `EventConsumerIsolationTest` (2) | FIXED |
| F02 P0 one tick exception starves all workers; poison row stops a lane | CONFIRMED. Reproduced: cycle tick threw `ArithmeticException: / by zero` | `EventWorker.lane()` isolates each lane. Per-row catch in cycle/points ticks and in segment start. | before/after runs of the poison test | `EventWorkerLaneTest`, `WorkerResilienceTest.poisonCycleRow…` | FIXED (no attempts/backoff columns yet → NEXT) |
| F03 / G1 P1 unpaid-order expiry only via admin endpoint | CONFIRMED (grep: no caller) | `OrderApi.tick()` shares `expireBatch` with admin expire. Tenant round-robin (4×20). New `orders` lane. | runtime (workers on, 8606): CANCELLED in 1.0 s, stock 1/1→2/0, 0 admin calls | `WorkerResilienceTest.backgroundExpiry…` | FIXED |
| N1 (new) auto-expired in-flight payments stay CLOSING because auto-checks stop after ~2 min | CONFIRMED by impact analysis: TTL 900 s > 5 checks | `PaymentClosingHandler` consumes `order.closing.v1` and re-arms the payment check (payment module only) | 211 historical closing events consumed, 0 failures | `WorkerResilienceTest.expiredInFlightPayment…` | FIXED |
| F05 / G2 payment long tail | CONFIRMED | Only the CLOSING re-arm (N1). No webhook, statement, or slow lane. | — | — | PARTIAL |
| F07 P1 any new endpoint defaults to MEMBER | CONFIRMED as latent: all 38 current member endpoints are legitimate | `SecurityConfiguration`: explicit `MEMBER_PATHS`, `anyRequest().denyAll()` | mutation (drop `/v1/journey-instances`) → test fails with 403 | `AuthorizationCoverageTest` (real handler mappings), updated `PersistedCommerceTest.protocolErrors…` | FIXED |
| F12 P2 lock-order inversion (create vs confirm/release) | Was INFERENCE, now CONFIRMED: MySQL `Deadlock found when trying to get lock` | `OrderService.settle()`: one canonical order, points→coupon→budget→entitlement→stock. Matches the documented order in `member-lifecycle-catalog-ui/BACKEND_ARCHITECTURE.md`. | before: `CannotAcquireLockException … Deadlock` | `PersistedCommerceTest.releaseLocksShared…` | FIXED |
| F15 unconsumed `order.closing/fulfilling` stay PENDING; no retention | CONFIRMED | `order.closing.v1` now has a consumer (N1) | — | — | PARTIAL |
| F16 top-heavy tests; EventWorker untested | CONFIRMED | Lane unit test plus a runtime run with workers on | `runtime-expiry.json` | `EventWorkerLaneTest` | PARTIAL |
| F18 boundary test ignores the app shell | CONFIRMED | `ModuleBoundaryTest.appShellReachesDomainsOnlyThroughApi`. Only the composition root may build pure kernels. | mutation (`DashboardController → OrderMapper`) → fails | `ModuleBoundaryTest` (3) | PARTIAL (SQL table ownership, Maven declared deps remain) |

Security (§14): authorization and privilege-escalation surface covered by F07. Concurrency covered by F12 (deterministic deadlock test). Duplicate processing covered by F01 (inbox dedup across retries). Transaction boundaries: F01 and F03, each with its own transaction.

## C. Architecture Before / After

- **Previous.** One `@Scheduled` loop called 9 ticks in sequence; any exception ended the loop. The dispatcher ran every consumer of an event in one transaction, so any failure rolled all of them back and ISOLATED the event for everyone. Expiry was admin-only. HTTP authorization used `anyRequest → ADMIN|MEMBER`. Release paths locked stock before budget and entitlement, the reverse of checkout.
- **Implemented.** Per-lane and per-row isolation. Per-consumer transactions over the existing inbox, with no schema change. A scheduled expiry lane plus CLOSING recovery through the outbox, owned by the payment module. Deny-by-default authorization with an executable allowlist check. A single lock order. An app-shell boundary fitness test.
- **Current.** Same modular monolith, same tables, same infrastructure. Failures now stay local to one consumer, lane, row, or tenant. The architectural rules are enforced by tests.
- **Remaining limitations.**
  - Isolation is per event, not per consumer: after 5 attempts the whole event is ISOLATED, but committed siblings keep their effects.
  - Due rows have no attempt or backoff state. A poison row keeps taking one slot per tick and logs a WARN each time.
  - Tenant fairness: every lane visits 4 tenants per tick (N4).
  - No metrics or alerts yet.

## D. Business Capability Changes

- **Added.** Automatic release of abandoned unpaid orders (stock, coupon, budget, points, entitlement), when `commerce.workers-enabled=true`. Automatic resolution of expired in-flight payments through a channel close or confirm.
- **Corrected.** Paid-order fulfillment and other sibling effects no longer depend on marketing, journey, or effects consumers succeeding. Checkout and cancel on the same campaign and SKU no longer deadlock.
- **Deferred.** Refund FAILED path (G3), identity (G6), maker-checker (G7), cart/address/shipping (G9), order refund projection (G4), budget and journey-coupon compensation (G5), notifications, and the rest listed in F.
- **Remaining risk.** Deploying with workers enabled will expire every already-overdue pending order on the first ticks. That is intended, but operators should know. `commerce_local` had 0 such orders on 2026-09-25.

## E. Verification Matrix

| Area | Verification | Result | Evidence |
|---|---|---|---|
| Build + all tests | `scripts/verify.sh`, real MySQL | 220 → **230 pass**, BUILD SUCCESS | `verify-baseline.log`, `verify-final.log` |
| Consumer isolation | fail-first test, then fix | fails before, passes after | `EventConsumerIsolationTest` |
| Worker isolation | poison-row test on the original code, then the fix; lane unit test | fails before, passes after | `WorkerResilienceTest`, `EventWorkerLaneTest` |
| Deadlock | deterministic two-transaction interleaving | Deadlock before, pass after | `PersistedCommerceTest` |
| Authorization | all real member mappings, unlisted paths for 3 roles, allowlist mutation | pass; mutation caught | `AuthorizationCoverageTest` |
| Boundary fitness | jdeps over the app shell, forbidden-edge mutation | pass; mutation caught | `ModuleBoundaryTest` |
| Runtime, workers on | packaged jar on 8606 against the test schema | expiry 1.0 s; authz 403/403/200 | `runtime-expiry.json` |
| Dispatcher cost (BEFORE/CHANGE/AFTER) | 300 events, local MySQL, 3 runs | 1 consumer: 2.5–3.0 s before vs 2.8–3.0 s after (parity). 3 consumers: 3.1–3.3 s before vs 4.7–5.5 s after (1 transaction → 3). | throwaway benchmark (not committed) |
| Browser e2e (Playwright, 21 specs) | on 8606, including the uncommitted UI | 16 pass / 5 fail, none from the backend change (see below) | `browser-e2e.log` |

The 5 e2e failures were classified from their error text:
- `catalog-merchandising`, `member-behavior`, `member-cycles` (`color-scheme` dark→light): the uncommitted Shop/theme rework.
- `coupon-deliveries`: expects `撤销 2`, but committed `86d7e0a` renders `撤销 / 保留 2 / 0`. The backend revoked both coupons correctly.
- `commerce`: `payment.paid.v1` stayed PENDING with **attempts=0**, starved behind 10,580 historical due events from 2,539 test tenants (N4). A tenant-scoped pump moved the order to PAID, and `order.paid.v1` (3 consumers) was delivered with 0 attempts.

## F. Remaining Evolution Roadmap

**NEXT**
- F06: Micrometer gauges (backlog age, ISOLATED count, UNKNOWN payments/refunds), alerts, and `traceId` in MDC.
- F02b: attempts/backoff for cycle/points/segment due rows; a separate lane for payment/refund I/O.
- N4: tenant-fair dispatch (oldest-due-first across tenants, or a per-tenant budget), measured against backlog age.
- F04/G3: refund `FAILED` state and capacity release. Design it with the real refund channel's failure semantics.
- F09/G7: shared approval (submitter ≠ approver) for campaign, journey, page, coupon batch, rule, and offer. This changes admin flows and the e2e suite, so it needs product sign-off.
- F13: move `MemberGrowthHandler`/`BenefitCompensationHandler` out of the app shell.
- F18b: SQL table-ownership fitness test and a declared-vs-used Maven dependency check.
- F14: `@ConfigurationProperties` for TTLs, attempt limits, and batch sizes.
- F15b: consume or skip `order.fulfilling.v1`; retention for event, inbox, command, and audit.
- G4 order refund projection; G5 budget and journey-coupon refund compensation; G8 audit reader; G14 store lifecycle; G15 stock ledger and adjustment.
- N2: `marketing-effects-v1` fails on 2026-09-23 legacy `order.created/cancelled` payloads (`MismatchedInputException`).
- N3: `member-cycle-benefit-v1` `DomainException` on assessed events of leftover test tenants. Triage both N2 and N3 before production data ages.
- N5: realign the e2e specs with the committed and WIP UI.

**LATER**
- F10: lift the 100-campaign and 100k-segment caps, and batch lifecycle scans (after load data).
- G12: one outbound notification channel. G13: holdouts. G16: FULLTEXT search.
- F17: incremental reformatting of dense files while touching them.
- A workers-only deployment profile.

**NOT_RECOMMENDED_CURRENTLY**
- Microservice extraction or saga replacement (F11). Kafka/Redis/workflow or rule engines.
- Merchant settlement (G11) until multi-merchant is a confirmed business line.

**BLOCKED**
- F08/G6 needs an IdP choice (OIDC provider, token lifecycle).
- G10 needs a WMS provider and address-key rotation design.

## G. Engineering Knowledge

1. **Per-consumer transactions over an existing inbox.**
   - *Problem:* a journey bug could stop a paid order from being fulfilled.
   - *Difficulty:* keep at-least-once delivery and exactly-once effects without a broker or a schema change.
   - *Original design:* one transaction per event.
   - *Improved design:* one transaction per consumer. The event row lock is re-taken each time. The `(consumer_id, event_id)` inbox row commits with that consumer's effect, so retries skip finished consumers. The delivered mark rides with the last consumer.
   - *Trade-off:* multi-consumer events cost N transactions instead of 1 (measured at ~1.6× for 3 consumers). Single-consumer events are unchanged.
   - *Verification:* the test fails first, then passes after the fix.
   - *Interview line:* "Isolation came from the dedup table we already had; the only cost was transaction count, which I measured."
2. **Proving a suspected deadlock instead of arguing about it.**
   - *Setup:* lock the budget row in one transaction the way checkout does, fire a cancel, then request the SKU row.
   - *Result:* the old order deadlocks deterministically. After one helper enforces the documented lock order, the same interleaving completes.
   - *Trade-off:* none at runtime, because the resources are identical and only their order changed.
3. **Scheduling an existing operation safely.**
   - Reusing the admin transition (`expireBatch`) kept one code path. Impact analysis then showed that automation creates CLOSING orders *after* payment auto-checks run out, which the manual flow had hidden.
   - The fix stays inside the payment module and is event-driven: it consumes `order.closing.v1`, so order-runtime does not call payment.
4. **Deny-by-default without lockouts.**
   - A hand-written allowlist is fragile: the first grep missed 3 multi-path mappings.
   - The executable check enumerates `RequestMappingHandlerMapping` and asserts that no member endpoint is blocked and no allowlist entry is stale. It was mutation-tested.
   - *Trade-off:* unknown paths now return 403 instead of 404. That was verified to have no client dependency.
5. **Separating environment problems from regressions.**
   - One runtime check "failed" because of a UTC+8 session clock in a test script.
   - One e2e case failed from backlog starvation; `attempts=0` proved the event was never tried rather than failed.
   - Both were diagnosed from data, not assumed.
