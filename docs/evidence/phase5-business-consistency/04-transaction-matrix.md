# Transaction and recovery matrix

| Operation | Database transaction | External call | Durable event / dedup | Crash window and recovery |
|---|---|---|---|---|
| Start payment | order status + unique attempt + command/audit | sandbox `ensure` after commit | command key `(tenant, actor, operation, key)` | crash before `ensure`: same payment ID is recreated in sandbox by later reconcile/check; real provider requires equivalent idempotent ensure contract |
| Confirm payment | payment row + event atomically | channel `observe` / `close` before transaction | unique provider transaction; payment version | observation succeeds but local write fails: reobserve same attempt; `UNKNOWN` remains visible |
| Apply payment to order | order state + reservations + order event + Inbox atomically | none | `(consumer, event)` Inbox PK | consumer crash rolls all effects back; pending event retries |
| Expire order | order state + reservation release or closing event atomically | none | order version, event fact unique | crash before commit leaves due row; after commit event is durable |
| Ship / deliver | fulfillment and order state/event in command transaction | sandbox WMS returns local proof only | one fulfillment row per order, command key | a future real external WMS must reconcile external success after local failure; current sandbox makes no external shipment |
| Grant benefit | `REQUESTED` + event, then grant state + ledger + Inbox | none for current internal grant | order/source unique key and grant version | failed consumer retries; full refund may revoke or record compensation debt |
| Issue coupon | quota + coupon + source command in one transaction | none | source unique key and conditional quota | rollback leaves quota available; repeated source returns same coupon |
| Mutate points | account + lots + ledger + source key in one transaction | none | order/refund/source unique keys | duplicate event/command cannot produce another ledger effect |
| Request aftersale | fulfillment block + case + lines + command in one transaction | none | one active case per order | rollback removes hold and case together |
| Request refund | payment cumulative reservation + refund attempt + case transition in one transaction | sandbox `ensure` during later reconcile | one refund per case | uncertain outcome retains capacity; query same refund ID |
| Confirm refund | refund row + success event atomically | channel `observe` before transaction | refund row lock, provider transaction unique key | external success with lost response is found by later observe; aftersale consumer completes separately |
| Consume event | Inbox insert + one consumer's side effects in one transaction | depends on handler; current critical handlers use internal DB effects | `(consumer_id,event_id)` PK | crash before commit retries; crash after commit skips successful consumer |

Decision: **KEEP_CURRENT_DESIGN** for the present MySQL event path. `Outbox.append` has `Propagation.MANDATORY`; business state and `platform_event` insert share the same transaction. `EventDispatcher` inserts Inbox and calls each handler in one consumer transaction. `PENDING` events survive restart and are discovered by the dispatcher. There is no reason to add a second Outbox or Inbox framework. This decision applies to the current sandbox adapters; a real provider adapter needs an independently verified idempotency and unknown-result contract.

## Explicit dual-write inventory

| Operation | DB Transaction | External Call | Event | Idempotency | Crash Window | Recovery |
|---|---|---|---|---|---|---|
| Start payment | order + attempt + command | sandbox `ensure` after commit | none until terminal fact | stable payment ID and command key | commit before channel ensure | same-ID ensure and observe |
| Confirm payment | payment fact + Outbox | observe provider fact before commit | `payment.paid.v1` / `payment.closed.v1` | row/version and provider transaction unique key | observed external fact before local commit | reobserve same ID |
| Settle order | order + reservations + Outbox + Inbox | none | `order.paid.v1` / cancellation | consumer/event Inbox key; order version | handler dies before commit | rollback and retry pending event |
| Expire order | order + release or closing + Outbox | none | closing/cancellation | due-state lock and version | selected stale candidate | recheck locked state; retry worker |
| Ship / deliver | fulfillment + order + command + Outbox | local WMS sandbox proof | order transition | fulfillment order key and status/version | sandbox proof before local commit | same command; real WMS would need query by obligation ID |
| Grant / revoke benefit | grant state + ledger + Inbox | none in current implementation | requested/completed | grant source key and version | consumer dies before commit | rollback/retry; compensation debt for consumed units |
| Issue / use coupon | quota + coupon + command | none | later order/aftersale event | source key, conditional quota/status | transaction dies | rollback/retry source |
| Grant / spend / restore points | account + lot + ledger + Inbox/command | none | source event where applicable | source/order/refund keys | transaction dies | rollback/retry source |
| Open aftersale | case + lines + fulfillment hold + command | none | none until decision | active-case and command key | hold before case completion | one transaction rollback |
| Request refund | payment reservation + refund attempt + case | sandbox ensure on later check | none until terminal fact | case unique key and cumulative cap | reserve before provider ensure | retain `UNKNOWN`, same-ID ensure/query |
| Confirm refund | refund fact + Outbox | observe provider fact before commit | `refund.succeeded.v1` | row lock and provider transaction unique key | external success before local commit | same-ID reobserve, then aftersale consumer |
