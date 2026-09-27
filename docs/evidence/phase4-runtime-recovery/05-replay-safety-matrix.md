# P4.4 — Replay Safety Model and Matrix

## 1. Replay semantics

- **Replay** means deliberately executing one consumer for historical `DELIVERED` events of one tenant, limited by event types, a time range and a maximum count. Event status never changes.
- Two modes:
  - `UNPROCESSED`: execute only when the consumer has **no inbox row** for the event. The `(consumer, event)` inbox insert is the dedup boundary; a replay can never execute a consumer twice for the same event.
  - `REPROCESS`: execute even if the inbox row exists. This is only allowed for a pure projection that recomputes from authoritative data.
- Not supported by design:
  - deleting inbox rows to "re-run" a consumer;
  - replaying PENDING, ISOLATED or SKIPPED events (that is recovery, see 04; PENDING events are the live lane's);
  - cross-tenant replay.

## 2. Classification (declared in code, `EventHandler.replaySafety()`)

- Categories: `PURE`, `IDEMPOTENT_WRITE`, `DEDUP_PROTECTED`, `COMPENSATABLE_SIDE_EFFECT`, `IRREVERSIBLE_SIDE_EFFECT`, `EXTERNAL_SIDE_EFFECT`, `FINANCIAL_SIDE_EFFECT`.
- Each declaration carries the flags `historicalReplay` and `reprocess`, plus an evidence string.
- The **default is `null` (unclassified)**, which the gate rejects. A consumer added later cannot become replayable by omission.
- `ReplayTest.everyConsumerIsClassifiedAndOnlyThePureProjectionIsReplayable` asserts that every registered consumer (10) has a classification with evidence.

## 3. Replay safety gate (`ReplayGate`, checked at job creation, at resume, and before every item)

1. The consumer is unknown or not classified → `UNKNOWN_CONSUMER` / `UNCLASSIFIED`.
2. **Hard rule:** any `FINANCIAL`, `EXTERNAL` or `IRREVERSIBLE` effect → `REPLAY_NOT_SUPPORTED`, **even if the declaration says `historicalReplay=true`**. This is tested with a stub declaring `PURE + FINANCIAL`.
3. `historicalReplay=false` → `REPLAY_NOT_SUPPORTED`.
4. `REPROCESS` requires `effects == {PURE}` and `reprocess=true`; otherwise → `REPROCESS_NOT_SUPPORTED`.

A rejection:
- at creation: 409, no job row, `commerce.replay.blocked` +1, and a `REPLAY_BLOCKED` WARN line (with the alert code on the next health evaluation);
- during execution: the job becomes `FAILED` with `last_error=GATE:<code>` before the consumer is called.

## 4. Matrix

The evidence comes from a source read of every `handle()` call chain, the mapper XML and the migrations. The notes are condensed in the table; file and line references are in the Evidence column.

| Work type (consumer) | Types | Side effect | Idempotency protection (besides inbox) | Business key / DB constraint | Replay safe? | Automatic replay | Manual recovery | Evidence |
|---|---|---|---|---|---|---|---|---|
| `marketing-effects-v1` | order.created/paid/ready/completed/cancelled, refund.succeeded | read-model projection only | re-reads order/quote/refund from authoritative APIs; `GREATEST()` monotonic upsert | PK `(tenant, order)` on `marketing_effect_order` (V22) | **YES** (PURE, UNPROCESSED and REPROCESS) | NO (replay is always an explicit job) | RETRY/SKIP | `MarketingEffectsService.java:22-60`, `EffectsMapper.xml:4`; the existing rebuild API relies on the same property |
| `order-payment-v1` | payment.paid/closed | order transition; confirm/release inventory, coupons, points, funding, entitlements | payload must equal the terminal payment row; order status early return; version CAS | `order_record` version, `uk_event_fact` for re-emitted facts | **NO**: FINANCIAL | NO | RETRY (inbox + CAS) | `PaymentService.java:64-72`, `OrderService.java:88-96` |
| `payment-order-closing-v1` | order.closing | resets payment check counters, which leads to a later remote `channel.close` | `status IN (UNKNOWN, OPEN)` | – | **NO**: EXTERNAL + FINANCIAL | NO | RETRY | `PaymentClosingHandler.java:13-16`, `PaymentMapper.xml:25`, `PaymentService.tick` |
| `member-growth-v1` | order.completed, refund.succeeded | growth, cycle contribution, **points credit** (expiry counted from *now*), level change → journeys | source-keyed rows; only `contribution − stored` is applied; account version CAS | PKs `member_growth_order`, `member_point_order`, lot id = hash(order) | **NO**: a historical order would grant fresh points and level changes (COMPENSATABLE) | NO | RETRY | `MemberGrowthHandler.java:15-27`, `MemberPointsService.java:106-142` |
| `benefit-refund-v1` | aftersales.completed | revoke benefits, restore coupons, cancel journeys | status CAS for coupon, hold, grant, journey | – | **NO**: benefit side effect (COMPENSATABLE) | NO | RETRY | `BenefitCompensationHandler.java:13-16`, `CouponMapper.xml:20-21` |
| `aftersale-refund-v1` | refund.succeeded | complete case, **return points**, unblock fulfillment | `COMPLETED` early return; `requireState`; version CAS | PK `member_point_return(tenant, case)` (V27) | **NO**: points side effect (COMPENSATABLE) | NO | RETRY | `AftersaleService.java:54-64`, `PointsSpendService.java:111-125` |
| `internal-entitlement-grant-v1` | benefit.grant.requested | **benefit grant** | only from REQUESTED with version == event version | `uk_entitlement_source` (V13) | **NO**: benefit (COMPENSATABLE) | NO | RETRY | `EntitlementService.java:43-46`, `EntitlementMapper.xml:16` |
| `member-cycle-benefit-v1` | member.cycle.assessed | level benefit grant | source hash plus current-cycle guard | `uk_entitlement_source` | **NO**: benefit (COMPENSATABLE) | NO | RETRY | `MemberBenefitService.java:54-75` |
| `journey-order-paid-v1` | order.paid, member.registered, member.level.changed, segment.member.entered | enrolment, later **coupons/grants/in-app notifications** | `uk_journey_entry(tenant, journey, version, event_key)`; occurred-time window filters | V14 | **NO**: a late enrolment continues into coupon and grant effects (COMPENSATABLE) | NO | RETRY | `JourneyService.java:150-169`, `JourneyMapper.xml:9,20` |
| `fulfillment-order-v1` | order.paid, order.ready | fulfillment record insert | `ON DUPLICATE KEY` | PK `(tenant, order)` (V5) | **NO**: would put stale paid orders into the shipping queue | NO | RETRY | `FulfillmentService.java:41-45`, `FulfillmentMapper.xml:5` |

### 4.1 Required financial / benefit protection details

| Workload | Idempotency mechanism | Dedup boundary | Business key | DB constraint | External provider protection | Replay permission |
|---|---|---|---|---|---|---|
| payment confirmation (`order-payment-v1`) | payload must equal the terminal payment row; order state machine | inbox `(consumer, event)` | order id + payment id | `order_record` version CAS; `uk_event_fact(tenant, type, aggregate, version)` blocks a double emit | no provider call in the handler | **REPLAY_NOT_SUPPORTED** |
| payment check / close (lane) | committed claim (`check_attempts` CAS) before the call | claim CAS | payment id | – | the channel request is idempotent by provider request id (Phase 3) | not replayable (not an event consumer); manual reconcile only |
| refund check (lane) | as payment | claim CAS | refund id | – | idempotent refund query | not replayable; manual reconcile |
| points grant/revoke (`member-growth-v1`) | net contribution delta | inbox | order id / refund id | PK `member_point_order`, `member_point_refund`; lot PK = hash(order) | – | **REPLAY_NOT_SUPPORTED** |
| points return (`aftersale-refund-v1`) | case state + PK | inbox | case id | PK `member_point_return` | – | **REPLAY_NOT_SUPPORTED** |
| points expiry (lane) | `remaining=0` re-check under the member lock | lot | lot id | lot CHECK constraints | – | recovery only |
| benefit grant (`internal-entitlement-grant-v1`, `member-cycle-benefit-v1`) | REQUESTED + version guard; source hash | inbox | source id | `uk_entitlement_source` | – | **REPLAY_NOT_SUPPORTED** |
| coupon restore (`benefit-refund-v1`) | status CAS | inbox | coupon id / hold | – | – | **REPLAY_NOT_SUPPORTED** |
| inventory confirm/release (inside `order-payment-v1` and order expiry) | reservation status per order | order CAS | order id | reservation rows per order | – | **REPLAY_NOT_SUPPORTED** |
| fulfillment / WMS | `ON DUPLICATE KEY`; WMS is only called by admin ship/deliver | PK | order id | PK | WMS not called by the consumer | **REPLAY_NOT_SUPPORTED** |
| notifications | `uk_journey_notification(instance, node)`; in-app only, no external channel | – | instance + node | V14 unique key | none (not remote) | not replayable (via journey) |

## 5. Why only one consumer is replayable

- No consumer relies on the inbox alone. Every second run with an existing inbox row would be a no-op because of a business key or a state CAS.
- The risk of replay is therefore not "twice for the same event". It is **executing business effects for old events that a consumer never processed**: fresh points and levels for year-old orders, re-armed remote closes, stale shipping records. The repository contains no product decision that makes such late effects acceptable.
- Following the brief (*REPLAY_NOT_SUPPORTED is acceptable and preferable*), these consumers are declared non-replayable. Their unfinished work is handled by **recovery**, which is audited and executes only unfinished consumers.
