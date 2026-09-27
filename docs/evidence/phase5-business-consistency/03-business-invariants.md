# Business invariant catalog

The test references below are executable MySQL tests unless marked `pending`. A code guard alone is recorded as design evidence, not proof.

| ID | Invariant and guard | Current executable evidence | Assessment |
|---|---|---|---|
| I1 | one payment per order; payment row lock/version and order CAS | `PersistedCommerceTest#paidFactWinsCancellationAndDuplicateDeliveryDoesNotDoubleConfirm`, `concurrentPaymentRechecksCommitOnePaidFactAndOneOrderEffect` | covered after new test passes |
| I2 | provider transaction cannot bind twice; `uk_payment_channel_transaction` | `oneProviderTransactionCannotPayTwoOrders`, `mismatchedChannelAmountCannotGeneratePaidEvent` | covered for sandbox |
| I3 | `CANCELLED` cannot become paid; `CLOSING` can become paid on proof | `expiryWithoutPaymentCanReleaseAndRejectLaterPayment`, `paidFactWinsCancellationAndDuplicateDeliveryDoesNotDoubleConfirm` | covered for sandbox |
| I4 | paid order cannot expire; due SQL excludes paid and lock rechecks | `paidProviderFactConvergesAfterExpiryStartsClosing`, `staleExpiryCannotCancelAnAlreadyPaidOrder`, `paymentAfterExpiryDiscoveryIsRecheckedUnderTheOrderLock`, `twoLiveAppInstancesConvergeWhenExpiryRacesWithPaidRecheck` | covered for sandbox |
| I5 | one fulfillment row per order; status/version guarded | `shipmentAndDeliveryAdvanceOrderAndRejectTrackingReplacement`, `duplicateConcurrentShipmentCommandsCreateOneShipmentTransition` | covered for sandbox WMS |
| I6 | one benefit source/grant; unique source and REQUESTED/version transition | `entitlementIsReservedThenGrantedOnceAfterTrustedPayment`, `refundBeforeDelayedGrantCannotResurrectRevokedEntitlement` | covered for current internal grant |
| I7 | points source, refund and order keys bound to ledger/account transaction | `MemberPointsTest#netCashSourcesHandleEarlyRefundDuplicatesAndOriginalPolicy`, `PointsCheckoutTest#partialReturnsConserveCashAndIntegerPoints` | covered for exercised paths |
| I8 | coupon issue source/claim unique, quota conditional update | `couponQuotaIsSafeAcrossDifferentMembers`, `PointOfferTest#couponExchangeIsAtomicIdempotentAndCannotBeClaimedForFree` | covered for exercised paths |
| I9 | refund cumulative reserve cannot exceed payment amount | `concurrentRefundReservationsCannotExceedReceivedAmount`, `partialReturnsUseOriginalAllocationAndNeverOverRefund` | covered |
| I10 | one active aftersale per order and fulfillment block | `simultaneousAftersaleApplicationsLeaveOneActiveCase` | covered |
| I11 | full refund/fulfillment reconciliation respects returned quantities | `beforeShipmentRefundBlocksShippingAndWaitsForRealRefundFact`, `partialReturnsUseOriginalAllocationAndNeverOverRefund` | covered for sandbox flow |
| I12 | business state and required event share transaction | `crashAfterPaymentConsumerEffectBeforeCommitRollsBackAndRetriesOnce`; `Outbox.append` mandatory transaction; removing the payment terminal event caused `concurrentPaymentRechecksCommitOnePaidFactAndOneOrderEffect` to fail | covered for tested path; mutation detected |
| I13 | consumer effect cannot survive failed upstream transaction | `failureRollsBackEffectsCommandAndAuditTogether`, `failedConsumerRollsBackInboxThenIsolatesAndAuditedRetryRecovers` | covered for exercised paths |
| I14 | partial result remains visible as `UNKNOWN`, `REFUNDING`, or compensation debt | `refundEvidenceMismatchKeepsCaseOpenAndDoesNotRestoreInventoryTwice`, `consumedEntitlementRefundCreatesExplicitCompensationDebt` | covered for exercised paths |
| I15 | unknown provider outcome has bounded query path | `backgroundReconciliationRecoversAfterChannelSuccessWithoutClientReturn`, `paidProviderFactConvergesAfterExpiryStartsClosing` | covered for sandbox; real provider not integrated |

## Partial benefit bundle decision

The repository currently uses independently keyed grants; it has no product-level all-or-nothing bundle contract. Do not infer one.

| Model | Retry and consistency | Customer / audit | Data and operator cost |
|---|---|---|---|
| Atomic bundle | all grants in one transaction or none; cannot atomically include irreversible external grants | customer sees full success or failure | bundle record plus one transaction; external side effects need separate uncertainty handling |
| Partial grant | each grant has own key/status and retries | customer may see A while B is pending/failed; each outcome must be disclosed | per-item progress and recovery, simpler than reversal |
| Compensating bundle | failed later grant triggers bounded reversal of earlier grants | customer may temporarily see an entitlement then revocation; audit must show both | compensation state and manual action for already consumed or irreversible benefits |

Product decision remains open. Existing independent grant behavior must not be presented as a promised atomic bundle.
