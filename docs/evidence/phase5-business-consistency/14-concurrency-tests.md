# Concurrency scenario ledger

All named Java tests use the isolated MySQL 8.4 test schema. Phase 5 tests in `PersistedCommerceTest` and `CouponDeliveryTest` passed in the final clean build (`commerce-app`: 264 run, 0 failures, 5 optional skips).

| Scenario | Intended final state | Executable test or applicability |
|---|---|---|
| C1 duplicate payment callback | one paid payment row, event, order settlement | No provider callback endpoint is installed. Two concurrent trusted rechecks cover the equivalent terminalization boundary: `concurrentPaymentRechecksCommitOnePaidFactAndOneOrderEffect`. |
| C2 payment vs expiry | paid proof wins; absent payment closes/cancels; paid order stays paid | `paidProviderFactConvergesAfterExpiryStartsClosing`, `staleExpiryCannotCancelAnAlreadyPaidOrder`, `expiryWithoutPaymentCanReleaseAndRejectLaterPayment`, `concurrentCloseAndChannelSuccessChooseOneDurableFact`. |
| C3 callback vs reconciliation | one paid fact | No callback endpoint; admin/member and background all call the same `reconcileInternal`. `concurrentPaymentRechecksCommitOnePaidFactAndOneOrderEffect` plus `backgroundReconciliationRecoversAfterChannelSuccessWithoutClientReturn`. |
| C4 duplicate fulfillment trigger | one fulfillment row/transition | `duplicateConcurrentShipmentCommandsCreateOneShipmentTransition`, `shipmentAndDeliveryAdvanceOrderAndRejectTrackingReplacement`; `fulfillment_record` PK and guarded status. |
| C5 fulfillment vs cancellation/after-sales | one legal fulfillment/aftersale order | `shipmentAndAftersaleRaceKeepsOneConsistentFulfillmentState`, `beforeShipmentRefundBlocksShippingAndWaitsForRealRefundFact`. Order cancellation after payment is rejected by the order state machine. |
| C6 duplicate benefit grant | one grant ledger entry | `entitlementIsReservedThenGrantedOnceAfterTrustedPayment` deliberately redelivers grant event. |
| C7 quota last unit | exactly one reservation, no negative quota | `lastEntitlementQuotaLetsOnlyOneConcurrentOrderReserve`; `PointOfferTest#concurrentRedemptionsRespectMemberAndGlobalLimits`. |
| C8 duplicate points mutation | one source mutation | `MemberPointsTest#netCashSourcesHandleEarlyRefundDuplicatesAndOriginalPolicy`, `PointsCheckoutTest#partialReturnsConserveCashAndIntegerPoints`. |
| C9 coupon use vs revoke/expiry | held coupon cannot be revoked; expired coupon cannot be newly used or renewed | `CouponDeliveryTest#couponReservationAndRevocationRaceHasOneWinner`, `revocationPreservesHeldCouponsAndReportsTheReason`, `relativeCouponSurvivesIssuanceWindowAndCancellationNeverRenewsIt`; `PersistedCommerceTest#partialRefundKeepsCouponUsedFullRefundReturnsAndOldEventCannotReleaseNewHold`. |
| C10 duplicate refund request | one refund per case, cumulative reserve bounded | `duplicateConcurrentRefundRequestsReserveThePaymentOnlyOnce`, `concurrentRefundReservationsCannotExceedReceivedAmount`; `uk_refund_case`. |
| C11 refund callback vs polling | one success event/case completion | No provider callback endpoint is installed. Two concurrent rechecks cover the terminalization boundary: `concurrentRefundRechecksEmitOneSuccessAndCompleteOneCase`. |
| C12 after-sales concurrent submission | one active case, other gets 409 | `simultaneousAftersaleApplicationsLeaveOneActiveCase`. |
| C13 after-sales vs fulfillment | either shipped then return-required, or blocked READY and shipping rejected | `shipmentAndAftersaleRaceKeepsOneConsistentFulfillmentState`. |
| C14 event redelivery | Inbox suppresses duplicated business effects | `paidFactWinsCancellationAndDuplicateDeliveryDoesNotDoubleConfirm`, `beforeShipmentRefundBlocksShippingAndWaitsForRealRefundFact`, `entitlementIsReservedThenGrantedOnceAfterTrustedPayment`. |
| C15 external success then local crash | reconcile stable external ID before another financial or shipment attempt | Current payment/refund/WMS adapters are local sandboxes, with no live third-party effect. `crashAfterPaymentConsumerEffectBeforeCommitRollsBackAndRetriesOnce` injects a failure after the order/stock effect but before Inbox commit; `backgroundReconciliationRecoversAfterChannelSuccessWithoutClientReturn` finds a sandbox paid fact after the client response is lost. Real provider/WMS integration is a separate acceptance gate. |

## Ordered expiry cases

| Case | Expected result | Evidence |
|---|---|---|
| E1 expiry discovers candidate, payment commits before expiry row lock | expiry skips changed state; paid stays paid | `paymentAfterExpiryDiscoveryIsRecheckedUnderTheOrderLock` uses the worker's candidate query, then commits payment, then calls its locked recheck. |
| E2 payment confirmation begins, expiry attempts at the same time | `CLOSING` may precede order payment event; paid fact eventually wins | `twoLiveAppInstancesConvergeWhenExpiryRacesWithPaidRecheck` starts both HTTP operations together; `concurrentCloseAndChannelSuccessChooseOneDurableFact` covers provider close/payment competition. |
| E3 expiry commits before late payment | no payment attempt can start from `CANCELLED` | `expiryWithoutPaymentCanReleaseAndRejectLaterPayment`. |
| E4 provider paid, callback/recheck delayed, expiry executes | `CLOSING` retains reservations, then `PAID` | `paidProviderFactConvergesAfterExpiryStartsClosing`. |
| E5 two application instances race expiry and callback | same state as E2/E4, one financial effect | `twoLiveAppInstancesConvergeWhenExpiryRacesWithPaidRecheck` starts a second Spring application with its own connection pool and transaction manager; `OrderExpiryLaneTest#concurrentInstancesExpireEachOrderExactlyOnce` covers four expiry workers. |

The current application has no provider callback endpoint, so C1/C3/C11 use the same trusted reconciliation boundary. A future callback adapter must be checked against the same database guards and provider-specific signature/ordering contract before activation.
