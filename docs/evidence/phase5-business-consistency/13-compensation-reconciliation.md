# Compensation and reconciliation decisions

| Failure path | Authority and temporary state | Convergence | Manual condition |
|---|---|---|---|
| Payment `UNKNOWN` after start or network timeout | local attempt is an intent, sandbox channel ledger is payment evidence | re-query same payment ID; paid proof commits `payment.paid.v1`; close proof commits `payment.closed.v1` | bounded automatic checks exhausted or evidence mismatch |
| Expired order with started payment | order `CLOSING` retains reservations | payment recheck closes or confirms; paid proof wins | no conclusive channel fact after bounded checks |
| Order `PAID`, downstream event pending or consumer failed | order and event rows are durable | dispatcher retries; isolated event uses governed recovery, successful consumers retain Inbox | poison event after retry budget; never generic replay of financial/entitlement effects |
| Fulfillment proof before local ship commit | current WMS sandbox only returns local proof; no third-party shipment is initiated | local command retry with same tracking number | future external WMS must add query/reconcile by stable obligation ID before use |
| Benefit grant requested but consumer fails | `REQUESTED` is visible; no available balance yet | same event retried; version guard and source key suppress duplicates | grant event isolated or consumed grant requires compensation debt decision |
| Partially consumed benefit then full return | `COMPENSATION_REQUIRED` and debt units | operator records recovery or write-off with reference | always requires explicit decision for consumed units |
| Coupon/points after full or partial return | completed aftersale is authority; partial return keeps coupon used; points restore only recorded original allocation | `aftersales.completed.v1` consumer and points refund source | event isolation or incompatible historical state |
| Refund channel outcome unknown | refund attempt `UNKNOWN` reserves capacity; aftersale `REFUNDING` | re-query same refund ID, then success event and aftersale completion | bounded checks exhausted, mismatch, or provider unavailable |

For the current sandbox, `ensure` is idempotent by payment/refund ID and `observe` reads a durable ledger. A real provider adapter must preserve stable request IDs, authentic result validation, and query-before-retry behavior; this Phase 5 run does not certify a third-party channel.

No database rollback is described as reversing a real external effect. No generic Saga, broker, scheduler, replay, or repair endpoint was added.
