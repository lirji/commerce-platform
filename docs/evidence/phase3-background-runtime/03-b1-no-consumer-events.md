# B1 — Events With No Registered Consumer

Status: **DECIDED** (engineering decision from repository evidence; no product decision needed).

## 1. Audit

### Emitted types (every `outbox.append` call site in `src/main`)

| Event type | Emitter | Local consumers (`EventHandler.consumer()`) |
|---|---|---|
| `order.created.v1` | `OrderService.create` | `marketing-effects-v1` |
| `order.paid.v1` | `OrderService.paymentFact` | `fulfillment-order-v1`, `marketing-effects-v1`, `journey-order-paid-v1` |
| `order.ready.v1` | fulfillment | `fulfillment-order-v1`, `marketing-effects-v1` |
| `order.closing.v1` | `OrderService.cancel`, `expireBatch` | `payment-order-closing-v1` (R1) |
| `order.cancelled.v1` | `OrderService` (cancel, expiry, payment absence) | `marketing-effects-v1` |
| `order.completed.v1` | `OrderService.fulfillmentFact(delivered=true)` | `member-growth-v1`, `marketing-effects-v1` |
| **`order.fulfilling.v1`** | `OrderService.fulfillmentFact(delivered=false)` | **none** |
| `payment.paid.v1`, `payment.closed.v1` | `PaymentService.reconcileInternal` | `order-payment-v1` |
| `refund.succeeded.v1` | `RefundService` | `member-growth-v1`, `marketing-effects-v1`, `aftersale-refund-v1` |
| `aftersales.completed.v1` | aftersales | `benefit-refund-v1` |
| `benefit.grant.requested.v1` | benefit | `internal-entitlement-grant-v1` |
| `member.cycle.assessed.v1` | member cycles | `member-cycle-benefit-v1` |
| `member.registered.v1`, `member.level.changed.v1`, `segment.member.entered.v1` | member / segments | `journey-order-paid-v1` |

`order.fulfilling.v1` is the **only** emitted type with no consumer.

### Is there an external consumer?

- No. `Outbox.append` writes only to `platform_event`. The only reader of `platform_event` in `src/main` is `EventMapper` (used by `EventDispatcher`). There is no relay, broker or CDC publisher. An `EXTERNAL_ONLY` classification is therefore impossible today: nothing would ever deliver such an event.
- WMS is external (`BACKEND_ARCHITECTURE.md`, `RISKS.md`), but WMS is the **source** of the shipment fact (`/v1/admin/fulfillments/{orderId}/ship`, s7 CONTRACTS), not a consumer of the order lifecycle event.

### Is it a published contract?

- No contract document (`docs/design/*/CONTRACTS.md`) lists `order.fulfilling.v1` as an event with a required consumer. s7 CONTRACTS only specifies the state transition `PAID→FULFILLING`.
- The order state itself is authoritative in `order_record.status` and `version`. A future consumer that needs historical shipments can rebuild them from `order_record` (and `fulfillment_*`) rather than from the event row.

### Runtime evidence

| Schema | `order.fulfilling.v1` rows | Delivered ever |
|---|---|---|
| `commerce_test_20260923` | 413 PENDING | 0 |
| `commerce_local` | 8 PENDING | 0 |

The row count grows with every shipped order and occupies the PENDING range of `ix_event_delivery` and `ix_event_tenant_due` forever.

### Replay / history

- The row must stay visible: it is the audit trail of the lifecycle transition and the input to any future replay.
- Deleting it, or marking it `DELIVERED`, would lie about delivery.

## 2. Classification

| Type | Class | Rationale |
|---|---|---|
| `order.fulfilling.v1` | **INTENTIONALLY_UNHANDLED** | A lifecycle fact with no in-process consumer, no relay and no contract requiring one. It is kept for audit and replay. |
| any other type with no consumer, not declared | **REQUIRED_CONSUMER_MISSING** | The default. Absence of a consumer for an undeclared type is treated as a deployment or wiring defect, never skipped silently. |
| type declared unhandled but also consumed | **CONFIGURATION_ERROR** | The declaration is stale. The application fails at startup (same policy as a duplicate consumer name). |
| `OBSOLETE_EVENT` | – | No emitted type is obsolete. The runtime still supports the reason code for future retirement. |
| `EXTERNAL_ONLY` | – | Not applicable (no relay). Not implemented as a skip reason, because skipping an externally delivered event would lose it. |

## 3. Decision

1. **New terminal status `SKIPPED`** with a machine-readable `skip_reason` (`NO_REGISTERED_CONSUMER`, `OBSOLETE_EVENT_TYPE`). Migration `V36` extends the status CHECK. `SKIPPED` means "no consumer existed when this fact was recorded"; it is not delivery.
2. **Declaration by the publisher.** A module that emits a type with no consumer must declare it with an `UnconsumedEventType` bean. `order-runtime` declares `order.fulfilling.v1 → NO_REGISTERED_CONSUMER`.
3. **Decided at write time.** `Outbox.append` inserts declared types directly as `SKIPPED`. No scheduler sweep and no index range scan is needed.
4. **Startup validation.** `EventDispatcher` fails fast when a declared type also has a registered consumer.
5. **Undeclared, unconsumed types stay `PENDING`** and are reported as `unrouted`. The new health code `EVENT_NO_REQUIRED_CONSUMER` fires while `unrouted > 0`. They are never auto-skipped.
6. **Existing rows.** `V36` converts the existing `PENDING` `order.fulfilling.v1` rows to `SKIPPED/NO_REGISTERED_CONSUMER` (8 rows local, 413 test). Rows are retained, not deleted.
7. **Replay.** The admin retry command accepts `SKIPPED` as well as `ISOLATED`. It moves the row back to `PENDING` for the currently registered consumers. This is the path for a future consumer that needs a specific historical event.
8. **The event is still emitted.** The contract is not obsolete, so emission is not stopped.

## 4. Rejected options

| Option | Why rejected |
|---|---|
| No-op acknowledger marking `DELIVERED` | Lies about delivery; indistinguishable from a real consumer. |
| Stop emitting | Changes a lifecycle fact without proof that it is obsolete. |
| Keep `PENDING` (Phase 2 state) | `PENDING` is not "ignored": the rows grow without bound in the scheduling index and hide real `REQUIRED_CONSUMER_MISSING` cases in the same `unrouted` count. |
| Dispatcher sweep marking `SKIPPED` | Needs a scan of the due range filtered by type; write-time insertion costs nothing. |
| Auto-skip every type without a consumer | Would silently drop events whose consumer was mis-wired or not yet deployed. |
