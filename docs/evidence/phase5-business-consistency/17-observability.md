# Operational signals and conflict contract

No production metric, alert code, or public error contract changed in Phase 5. The audit found existing durable diagnostic fields sufficient for the current sandbox flows:

- payment/refund rows retain status, attempt count, next check, error class/evidence, and provider transaction reference;
- `platform_event` retains pending/isolation state, retry counts, first/last failure, and consumer errors without sensitive payload logging;
- `platform_inbox` records per-consumer completion;
- `platform_command` and `platform_audit` retain command idempotency and actor operation evidence;
- `platform_recovery` records governed operator recovery;
- entitlement compensation debt remains explicit in the benefit state and ledger.

`ApiErrors` maps `DomainException.CONFLICT`, `IDEMPOTENCY_CONFLICT`, and `ILLEGAL_TRANSITION` to 409. The observed `/aftersales` 409 is a valid precondition failure when the paid-order fulfillment record has not committed. The browser test now waits for that record; backend conflict behavior remains unchanged. Authentication and tenant ownership continue to be enforced by the existing controller/security and SQL scope. The newly added tests exercise the same authenticated admin/member routes and fresh tenant IDs.

No metric label with tenant, order, or payment ID was added. New alert codes would duplicate the existing retry/isolation signals without a new production failure mode to alert on; future real provider integration should revisit unknown-outcome backlog thresholds.
