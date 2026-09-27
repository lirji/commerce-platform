# Phase 5 business consistency map

Current implementation, inspected on `fix/phase5-business-consistency`. The listed transaction is the local MySQL transaction; the sandbox channel is a separate durable ledger.

| Flow and transition | Trigger / transaction | Business key and concurrency guard | Event / continuation | Failure outcome |
|---|---|---|---|---|
| Order creation → `PENDING_PAYMENT` or zero-cash `PAID` | `OrderService.create`, `Commands.run` | `(tenant, quote)` unique; stock, points, coupon, budget and entitlement reservations in command transaction | `order.created.v1`; zero-cash `order.ready.v1` | transaction rolls back reservations and event together |
| Payment start → `PAYMENT_IN_PROGRESS` | `PaymentService.start`, `Commands.run` | order row lock; `(tenant, order)` unique payment attempt | sandbox `ensure` after commit; later check lane | unknown channel result keeps the attempt for query |
| Channel observation → `PAID` / `CLOSED` | manual reconcile or payment check lane, `TransactionTemplate` after channel query | payment row lock, version and nonterminal status guard, provider transaction unique key | `payment.paid.v1` / `payment.closed.v1` in same transaction | channel query error leaves durable attempt for bounded recheck |
| Payment fact → order `PAID` / `CANCELLED` | `order-payment-v1` consumer transaction | event payload equals current terminal payment row; order row lock and version guard | settles reservations; `order.paid.v1` / `order.cancelled.v1` | failed consumer rolls back Inbox and all effects; retry / recovery |
| Order expiry → `CANCELLED` / `CLOSING` | admin command or expiry lane, per-order transaction | due state rechecked under `FOR UPDATE SKIP LOCKED`; order version guard | cancel releases reservations; closing event rearms payment checks | unknown payment cannot release stock; channel result determines final state |
| Fulfillment `READY` → `SHIPPED` → `DELIVERED` | `fulfillment-order-v1`, admin commands | `(tenant, order)` PK; fulfillment row lock, status/version guards | order `FULFILLING` / `COMPLETED` event in command transaction | sandbox WMS proof precedes local write; no actual external shipment adapter exists |
| Benefit `RESERVED` → `REQUESTED` → `AVAILABLE` | order payment transaction, then grant consumer | `(tenant, order)` or `(tenant, source type, source id)` unique; quota conditional update; version guard | `benefit.grant.requested.v1`; grant ledger in consumer transaction | consumer failure rolls back Inbox and grant; compensation debt recorded for consumed grants |
| Coupon issue / hold / use / refund | claim or source command, order settle, full-return consumer | source unique key, definition row lock and `issued < quota`, coupon conditional status update | full return via `aftersales.completed.v1` | held or expired coupon cannot be silently restored; refund restore policy is full return only |
| Points grant / spend / expiry / refund | order and refund events, member or points commands | source/refund/order keys; member row lock; lot/account/ledger same transaction | expiry lane and event consumers | duplicate source ignored or rejected; debt and expired forfeiture remain explicit |
| Aftersale `REQUESTED` → `WAIT_RETURN` / `REFUNDING` → `COMPLETED`, or `REJECTED` | member request, admin decision, refund consumer | fulfillment row lock and `blocked`; one active case per order; case version guard | refund request in approval transaction; completion event | 409 on missing unpaid fulfillment or active conflict; refund remains visible while unknown |
| Refund `UNKNOWN` → `SUCCEEDED` | case approval, then admin or lane reconciliation | case unique key, payment `refund_reserved` conditional cumulative cap, refund row lock and channel transaction unique key | `refund.succeeded.v1` in success transaction; aftersale consumer | unknown occupies refund capacity; query same refund ID before deciding completion |

## Order state graph

`PENDING_PAYMENT → PAYMENT_IN_PROGRESS → PAID → FULFILLING → COMPLETED`.
`PENDING_PAYMENT → CANCELLED` for no started payment.
`PAYMENT_IN_PROGRESS → CLOSING → CANCELLED` only after channel closure proof.
`PAYMENT_IN_PROGRESS` or `CLOSING → PAID` when the provider proves payment.
No transition leaves `COMPLETED` or `CANCELLED`.

The code path is `OrderLifecycle.apply` → `OrderService.transition` → `OrderMapper.change` with expected version. Expiry candidates are only `PENDING_PAYMENT` and `PAYMENT_IN_PROGRESS`; a paid order is excluded and the locked row is rechecked. `CLOSING` retains reservations until a payment fact resolves it.

## Source locations

- `order/src/main/java/com/lrj/commerce/order/domain/OrderLifecycle.java`
- `order-runtime/src/main/java/com/lrj/commerce/ordering/order/application/OrderService.java`
- `order-runtime/src/main/resources/mappers/ordering/OrderMapper.xml`
- `payment/src/main/java/com/lrj/commerce/payment/charge/application/PaymentService.java`
- `payment/src/main/java/com/lrj/commerce/payment/refund/application/RefundService.java`
- `fulfillment/src/main/java/com/lrj/commerce/fulfillment/application/FulfillmentService.java`
- `aftersales/src/main/java/com/lrj/commerce/aftersales/application/AftersaleService.java`
- `benefit/src/main/java/com/lrj/commerce/benefit/entitlement/application/EntitlementService.java`
- `benefit/src/main/java/com/lrj/commerce/benefit/coupon/application/CouponService.java`
- `member/src/main/java/com/lrj/commerce/member/points/application/MemberPointsService.java`
- `member/src/main/java/com/lrj/commerce/member/points/application/PointsSpendService.java`
