# P3.0 — Background Runtime Inventory (before Phase 3)

Source: repository code at the Phase 2 working tree. Every row was read from source; file references are relative to the repo root.

## Topology

```text
@Scheduled(fixedDelay=1000) EventWorker.deliver()     ONE scheduling thread (Spring default pool size 1)
  payments → refunds → orders → events → segments → journeys → cycles → points → deliveries → catalog-jobs
  each lane wrapped in try/catch (R1); a lane's run time delays every later lane in the same cycle
```

- This is the **only** production scheduler. No `@Async`, executors, timers, listeners, runners or cleanup jobs exist in `src/main`.
- There are no remote HTTP/MQ clients in `src/main`. Payment, refund and WMS channels are local sandbox adapters (`Sandbox*Channel`), but the lanes are written against `PaymentChannel`/`RefundChannel` ports, whose real implementations will be remote.
- Admin endpoints run the same per-tenant work on request threads (`/v1/admin/events/pump`, `/admin/orders/expire`, `/segments/pump`, `/admin/journeys/pump`, coupon-delivery `/pump`, `/catalog-jobs/pump`). They rely on row locks, not on the `synchronized` tick.
- Only the event lane has metrics (`commerce.events.*`).

## Lane table

| Lane | Entry point | Tenant-scoped? | Tenant selection | Work per visit / tick | Transaction boundary | Locking | Retry model | Failure semantics | Idempotency | External dependency | Metrics |
|---|---|---|---|---|---|---|---|---|---|---|---|
| events | `EventDispatcher.tick` | yes | Phase 2: fresh lane + 50-tenant cursor rotation, 1 s / 2,000-attempt budget | ≤5 events per tenant visit | one tx per (consumer, event) | `FOR UPDATE SKIP LOCKED` + inbox | 2/4/8/16 s, ISOLATED at 5 | any exception counts toward 5 | `platform_inbox` | consumers (in-process) | `commerce.events.*` |
| orders (expiry) | `OrderService.tick` (R1) | yes | `expiryTenants`: `DISTINCT tenant_id … tenant_id>cursor ORDER BY tenant_id LIMIT 4` | ≤20 orders per tenant | **one tx for all 20 orders of a tenant** | `expired … FOR UPDATE SKIP LOCKED` | none | exception rolls back the whole tenant batch; logged | status/version CAS | none | none |
| payments (check / close) | `PaymentService.tick` | yes | `dueTenants … LIMIT 4` | ≤5 checks per tenant (≤20 per tick) | claim UPDATE commits first; channel call outside tx; result in own tx | claim CAS on `check_attempts` | 4/8/16/32/64 s; auto checks stop at 5; `order.closing` re-arms | **every** failure consumes a check; logged | claim CAS, status/version CAS, idempotent channel request | payment channel (remote in prod) | none |
| refunds (check) | `RefundService.tick` | yes | `tenants … LIMIT 4` | ≤5 per tenant | as payments | claim CAS | 4…64 s; stops at 5; manual reconcile | every failure consumes a check | claim CAS, status CAS | refund channel (remote in prod) | none |
| segments | `SegmentService.tick` | yes | UNION query `… LIMIT 4` | 1 start + 1 batch (100 members) + 1 announcement (≤100 events) | one tx per phase; failure recorded in own tx | `lockRun` SKIP LOCKED, `lockRoot` blocking | batch: 2…32 s, ISOLATED at 5; announcement: 30 s fixed, stops at 5 | **a start failure has no counter: the same segment is re-picked first forever and blocks the tenant's other segment starts** | checkpoint + outbox unique key | in-process | none |
| journeys | `JourneyService.tick` | yes | UNION query `… LIMIT 4` | ≤5 scans × 4 steps + ≤5 instances | one tx per step / node (READ COMMITTED) | member `FOR UPDATE`, instance SKIP LOCKED | 2…32 s, ISOLATED at 5 | per item; deadline-past instances ignore backoff (UNVERIFIED impact) | unique entry/effect keys, version CAS | in-process | none |
| cycles | `MemberCycleService.tick` | **no rotation** | one global `due … LIMIT 20` ordered by due time | ≤20 members **globally** | one tx per member | blocking member lock | **none** | **20 persistently failing members stall assessment for every tenant**; one tenant's backlog delays all tenants (global FIFO) | version CAS + outbox key | none | none |
| points (expiry) | `MemberPointsService.tick` | **no rotation** | one global `due … LIMIT 20` by `expires_at` | ≤20 lots **globally** | one tx per lot | blocking member/lot/account locks | **none** | same as cycles: global FIFO and 20-row head-of-line poison | lot lock + `remaining==0` check | none | none |
| deliveries (targeted coupons) | `CouponDeliveryService.tick` | yes | `DISTINCT tenant_id … LIMIT 4` | 1 batch × ≤20 recipients | one tx per recipient | blocking batch lock | 2…32 s, ISOLATED at 5 | per batch; no log line | coupon source unique key | in-process | none |
| catalog-jobs | `CatalogJobService.tick` | yes | `DISTINCT tenant_id … LIMIT 4` | 1 job × ≤20 SKUs | one tx per SKU | blocking job lock, SKU lock | 2…32 s, ISOLATED at 5 | lock-wait failure is not counted (job re-picked each tick); no log line | per-item idempotency key, receipts PK | in-process | none |

## Risk assessment

| Lane | Backlog risk | Tenant starvation risk | Cross-lane risk | Business impact | Recommended migration |
|---|---|---|---|---|---|
| events | High (history) | Solved in Phase 2 | Uses up to ~1 s per cycle, doubling every other lane's period | High | Keep. Add failure classes, SKIPPED, retry fairness. |
| orders expiry | Medium (abandoned carts) | ⌈T/4⌉ s; **one poison order blocks its tenant's expiry forever** (the same 20 rows are re-selected and rolled back) | Blocked by events backlog | **High**: holds inventory, coupons, points, budget | Rotation + per-order tx + retry/quarantine state. |
| payments | Low–medium | ⌈T/4⌉ s | A slow channel call blocks all 10 lanes | **High (money)** | Rotation; transient failures must not consume checks; own thread. |
| refunds | Low | ⌈T/4⌉ s | Same as payments | **High (money)** | As payments. |
| points | Medium (lots expire in bulk) | **Global FIFO**: one tenant's bulk expiry delays every tenant | Normal | Medium | Tenant rotation. |
| cycles | Medium (cycle rollover) | **Global FIFO** | Normal | Medium | Tenant rotation. |
| segments, journeys, deliveries, catalog-jobs | Low–medium | ⌈T/4⌉ s | Normal | Medium | Rotation (shared primitive); item-level gaps recorded as limitations. |

## Findings that change the plan

1. **The single scheduling thread is the cross-lane problem.** Every lane's run time adds to every other lane's period. A payment channel timeout (typically seconds) or an event backlog tick (~1 s) delays order expiry and payments.
2. **Order expiry has a tenant-level poison bug** (R1 code). `expireBatch` handles 20 orders in one transaction. `inventory.release`/`coupons.release` throw `CONFLICT` on inconsistent holds, the batch rolls back, and the next tick selects the same 20 rows.
3. **Member cycles and points are not tenant-fair at all.** They use a global `ORDER BY due LIMIT 20` with no attempt state.
4. **Payment/refund checks convert transient failures into exhausted checks** in 4+8+16+32+64 s ≈ 2 min. Payments are re-armed by `order.closing`; refunds are not.
5. **Item lanes with ISOLATED states (segments, journeys, deliveries, catalog-jobs) have the same 5-attempt, ~1 min budget as events.** A dependency outage of about a minute isolates them.
