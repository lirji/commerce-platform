# Phase 4 closure gate

## Intermittent `/aftersales` 409

Classification: **TEST_RACE_ONLY** for the observed `operations.spec.ts` failure.

1. The browser test starts a payment, writes a sandbox `PAID` fact, and calls payment reconciliation. That transaction changes `payment_attempt` and appends `payment.paid.v1`; it does not synchronously change the order or create a fulfillment row.
2. `EventDispatcher` must consume the payment event (`order-payment-v1`), committing the order's `PAID` transition and `order.paid.v1`. A later `fulfillment-order-v1` consumer creates `fulfillment_record`.
3. The old `events()` helper stopped on the first manual pump result of zero. With the background event lane enabled, `FOR UPDATE SKIP LOCKED` can make the manual pump see zero while a worker holds an event, so zero is not a completion signal.
4. `AftersaleService.request` calls `FulfillmentService.holdForAftersale`; `ensurePaid` rejects an order that is still `PAYMENT_IN_PROGRESS`, producing the valid HTTP 409. No aftersale case was committed.
5. The spec now polls the *particular order's fulfillment record*, pumping when possible, before requesting aftersale. This waits on the actual business precondition and contains no fixed sleep.

Requested operation: member `POST /v1/aftersales`. Current aggregate at the failure: local payment `PAID`, order not yet `PAID`, fulfillment absent. Expected aggregate for request: order `PAID` or later and fulfillment `READY` or later. Competitor: background event dispatcher. Transaction order: payment fact commit → payment consumer commit → fulfillment consumer commit → aftersale command. The 409 arises when aftersale runs before the middle two commits. The business conflict check remains intact.

The backend already has `PersistedCommerceTest#simultaneousAftersaleApplicationsLeaveOneActiveCase`, which proves competing valid requests yield one case and one 409. The browser rerun result is recorded in the Phase 5 regression report.

## Runtime baseline freeze

Phase 4 replay fail-closed rules and the runtime architecture remain unchanged. Phase 5 source changes are confined to a browser readiness assertion and new business integration tests at this checkpoint. No scheduler, replay, retention, or recovery implementation was changed.
