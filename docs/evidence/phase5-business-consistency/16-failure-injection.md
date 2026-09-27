# Failure injection and mutation evidence

## Consumer crash boundary

`PersistedCommerceTest#crashAfterPaymentConsumerEffectBeforeCommitRollsBackAndRetriesOnce` runs the real `PaymentService` event handler inside a separate `EventDispatcher` transaction, then throws after the order and stock effect but before consumer commit. The test observes the order still `PAYMENT_IN_PROGRESS`, stock still held, and no `order-payment-v1` Inbox row. After making the event due and invoking the normal dispatcher, it observes one `PAID` order and one sold unit. It then redelivers the event and observes no second stock effect. This tests a transaction-level crash window; it is not a process kill or a real payment provider call.

## Must-detect mutations

Harness: `scripts/mutate.py`. Backups and raw logs are kept under ignored `.local/phase5-mutations/`; no backup was placed next to source files. Each run changed exactly one source anchor, installed the affected module, ran exactly one target test, restored the original bytes, checked SHA-256, and reinstalled the restored module.

| Mutation | Target test result with guard removed | Restored |
|---|---|---|
| Expiry candidate query includes `PAID` | `staleExpiryCannotCancelAnAlreadyPaidOrder`: 1 run / 1 failure; expected HTTP 200 for no-op expiry, got 409 `ILLEGAL_TRANSITION` | yes |
| Payment terminalization omits durable event | `concurrentPaymentRechecksCommitOnePaidFactAndOneOrderEffect`: 1 run / 1 failure; expected one `payment.paid.v1`, found zero | yes |
| Refund request omits cumulative payment reservation | `concurrentRefundReservationsCannotExceedReceivedAmount`: 1 run / 1 failure; both competing refunds succeeded | yes |

All three mutations were detected by the named business tests. This proves the tests are sensitive to these three protections; it does not assert mutation coverage for every guard in the system. The final clean build runs after the restored-source hash checks, so no mutation class or XML can be packaged.
