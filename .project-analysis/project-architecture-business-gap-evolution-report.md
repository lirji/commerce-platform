# Commerce Platform — Architecture, Business Capability Gap & Evolution Report

- Date: 2026-09-25
- Baseline: `main` @ `86d7e0a` plus the working tree. The uncommitted frontend layout changes (8 files) were read but not judged.
- Mode: `READ_ONLY_ANALYSIS` (routed through `project-capability-discovery`). No product code, tests, config, or schema was changed. No builds, tests, or DB queries were run. Test behavior is quoted from source, not from execution.
- Evidence labels: **FACT** (seen in code/config/schema), **INFERENCE** (reasoned from facts), **UNKNOWN** (not provable from the repo). Paths are relative to the repo root. Line numbers refer to dense one-line-per-method Java, so they point at whole methods.
- Prior artifacts compared: `.engineering/exploration/*` (2026-09-23, baseline V1–V15), `.cursor/project-analysis/architecture-risks.md`, `docs/design/unified-commerce/BACKEND_ARCHITECTURE.md`. Most gaps they list for member, catalog, and marketing have since been closed by V16–V34. This report re-verifies against current code.

---

## 1. Executive Summary

**What it is.** It is a DDD **modular monolith**: Java 21, Spring Boot 4.1, MyBatis, one MySQL schema, and one process that serves the REST API, background workers, and a same-origin React admin/member SPA. It covers brand-owned commerce plus membership and marketing operations:
- Member lifecycle, levels, points
- Catalog, stores, inventory
- Campaigns, rules, segments, coupons, entitlements
- Quote → order → pay → fulfil → return/refund
- Durable marketing journeys and a governed low-code ops page builder

The code base is small and very dense: 167 main Java files (~540 KB), 34 Flyway migrations, 87 tables, and ~182 HTTP endpoints. It was built in 43 commits over 2026-09-23/24 by a single author.

**How it is structured.** There are 19 Maven modules, one per bounded context plus two pure kernels (`marketing` rules, `order` state machine), `platform-runtime` (commands/outbox/inbox/credentials), `shared-kernel` (Money), and the `commerce-app` shell. A jdeps test enforces that cross-module Java calls go only through `.api` packages. Each table has exactly one owning module. The shared MySQL transaction is the main integration mechanism.

**What it does well (FACT).**
- Consistency engineering is unusually strong for its size:
  - Idempotent commands with request-hash replay
  - Transactional outbox and inbox
  - Conditional-update reservations for stock, budget, coupons, points, and entitlements
  - An explicit `CLOSING` state so an unknown payment never releases resources
  - Cent-exact largest-remainder allocation and conserving partial refunds
  - Journeys with a CAS checkpoint per node
  - Lot-based points with debt and expiry relief
- Almost all of this is tested against real MySQL, with concurrency races.

**What is missing.** Production-grade edges:
- Real identity (no login, IdP, or token lifecycle).
- Real payment, refund, and WMS adapters (sandboxes only, no webhooks, no statement reconciliation).
- Automated unpaid-order expiry.
- Refund failure handling.
- Fine-grained RBAC and maker-checker.
- Observability: metrics, alerts, trace correlation.
- Commerce basics: cart, address book, shipping fees.
- Settlement.
- Outbound notification channels.

**What is risky.** The async backbone has weak fault isolation:
- All consumers of an event run in **one transaction**, so a marketing failure blocks fulfillment of a paid order.
- Nine workers share **one scheduler thread**, and some ticks let exceptions escape.
- Isolation after about 30 s of failure is silent, because nothing alerts.

Several hard-coded scale ceilings exist: 100 campaigns per store, 100k members per segment, and one member per transaction in lifecycle scans.

**What should happen next.**
1. **Stabilize the async backbone and the order lifecycle edges.** Covers per-consumer transactions, worker isolation, scheduled expiry, refund failure states, and alerting.
2. **Harden identity and authorization.**
3. **Build the missing commerce and settlement capabilities.**

Keep the monolith. Nothing in the repo justifies extracting services yet.

---

## 2. Current Architecture

### 2.1 Technology inventory

| Area | Current Technology | Evidence | Role |
|---|---|---|---|
| Language/build | Java 21, Maven multi-module (19 modules), surefire `failIfNoTests` | `pom.xml:10,14-34` | FACT |
| Framework | Spring Boot 4.1.1 (webmvc/Tomcat), graceful shutdown | `pom.xml:36`, `application.yml` | FACT |
| Persistence | MyBatis 4.0.1 XML mappers, 10 s statement timeout | `pom.xml:37`, `application.yml:35-40` | FACT |
| Database | MySQL 8.4, Hikari pool 8, `innodb_lock_wait_timeout=5` | `application.yml:22-26`, `scripts/ci-configure.py:15` | FACT |
| Migrations | Flyway V1–V34, all in `commerce-app` | `commerce-app/src/main/resources/db/migration` | FACT |
| Messaging | **MySQL outbox/inbox only**, no broker | `platform-runtime/.../Outbox.java`, `EventDispatcher.java` | FACT |
| Scheduler | One `@Scheduled(fixedDelay=1000)` method, gated by `commerce.workers-enabled` (default false, true in compose) | `commerce-app/.../EventWorker.java:7-13`, `compose.yaml:18` | FACT |
| Security | Spring Security, custom Bearer filter, SHA-256 token lookup in `platform_credential` | `commerce-app/.../SecurityConfiguration.java:21-50` | FACT |
| Crypto | AES-256-GCM address encryption, AAD=tenant/order, single key, no key id | `order-runtime/.../AddressCipher.java:17-28` | FACT |
| Observability | Actuator `health` only; 7 logger calls in main code; traceId in error body only (no MDC) | `application.yml:41-48` | FACT |
| Frontend | React 19, antd 6, Vite 8, TS 7, hash routing, no state/query library; Playwright e2e | `frontend/package.json`, `frontend/src/app/App.tsx:44-50` | FACT |
| Container/CI | Distroless-style Temurin JRE (digest-pinned, uid 10001, read-only fs); GitHub Actions: build + real-MySQL tests + npm audit + smoke + Playwright | `Dockerfile`, `.github/workflows/verify.yml` | FACT |
| **Absent** | Redis/cache, Kafka/Rabbit, Micrometer/OTel, ShedLock/Quartz, resilience4j, OAuth2/JWT, method security, rate limiting, OpenAPI, search engine, object storage, Testcontainers | grep over java/xml/yml | FACT |

### 2.2 Runtime topology

```text
Browser (React SPA: ADMIN console / OPERATOR catalog page / MEMBER shop)
   │  same-origin, Bearer token (pasted, memory-only)
   ▼
commerce-app  (single Spring Boot process, 127.0.0.1:8602, 768 MiB)
   ├─ TokenFilter → Actor(tenant, actor, role, channel)   [platform_credential]
   ├─ 25 thin @RestControllers (all in commerce-app)
   │     └─ Commands.run(idempotency + audit + business tx)
   ├─ Domain modules via .api interfaces (in-process calls)
   │     member · merchant · store · catalog · inventory · trade · order-runtime
   │     payment · fulfillment · aftersales · marketing-runtime(campaign)
   │     benefit · marketing-automation(journey/ops/insight)
   │     pure kernels: marketing (rule AST/decision) · order (state machine)
   ├─ Outbox (platform_event, same tx as business change)
   └─ EventWorker thread (1 s): payments → refunds → events → segments →
        journeys → cycles → points → coupon deliveries → catalog jobs
   ▼
MySQL (external dev-infra instance, one schema, 87 tables, tenant_id on every table)
   Sandboxes (DB-backed) stand in for payment / refund / WMS channels
```

### 2.3 Module dependency map (Maven, FACT)

```text
shared-kernel ← marketing (rules), order (state machine)
shared-kernel ← platform-runtime ← member, merchant
merchant ← store ← catalog ← inventory
member, store ← benefit
catalog, member, marketing, store, benefit ← marketing-runtime (campaign)
member, store, catalog, marketing-runtime, benefit ← trade
trade, inventory, member, store, benefit, order ← order-runtime
order-runtime ← payment, fulfillment
order-runtime, fulfillment, payment, inventory, member ← aftersales
marketing-runtime, benefit, member, store, aftersales ← marketing-automation
everything ← commerce-app
```

- The longest chain is 8 deep: automation → aftersales → payment → order-runtime → trade → campaign → benefit → member.
- Several modules import transitive modules without declaring them (INFERENCE from an import scan): marketing-automation imports order-runtime, payment, and trade; commerce-app imports aftersales, payment, fulfillment, inventory, and order-runtime.

### 2.4 Data flow and integration style

- **Synchronous (in-process, one local transaction):** quote; order create (6 resource reservations); payment start; aftersale approve (restock + refund reservation); ship/deliver; point redemption.
- **Asynchronous (DB outbox, at-least-once, inbox dedup):** payment fact → order; order paid/ready → fulfillment, effects, journeys; refund succeeded → aftersale → coupon/entitlement/journey compensation; order completed/refunded → growth, points, behavior; cycle assessed → level benefits; segment entered → journeys.
- **Polling workers:** payment/refund re-query, segment runs, journey nodes, cycle assessment, point expiry, coupon batches, catalog batch jobs.

---

## 3. Business Capability Map

Maturity uses the prompt's scale. "MATURE" means the domain logic is complete and tested for local scope; it does not mean production-proven.

| Domain | Capability | Owning module | Maturity | Evidence | Gap |
|---|---|---|---|---|---|
| **Member** | Member record, status ACTIVE/FROZEN/CLOSED + change history | member | PARTIAL | `MemberService.java:18-52`, V16 | Admin-created only; no self-registration or profile maintenance |
| | Growth value & levels (versioned policy, net contribution per order) | member | MATURE | `MemberGrowthService.java:56-93`, `MemberGrowthTest` | `member_level` has 3 writers (see §8) |
| | Cycle assessment with retention/downgrade | member | MATURE (logic) | `MemberCycleService.java:84-134`, `MemberCycleTest` | Every member falls due at once at each period boundary; 20 members/s (§11) |
| | Points: lots, expiry, holds at checkout, refund to original lot, debt | member | MATURE | `PointsSpendService.java`, `MemberPointsService.java`, V25–V28 | — |
| | Tags, behavior events, 30-day profile | member | PARTIAL | `MemberBehaviorService.java:28-41`, V30 | Only BROWSE/ADD_TO_CART; no external behavior ingestion |
| | Identity / login / account | platform-runtime | **MISSING** | `SecurityConfiguration.java:18` ("外部IdP适配尚未启用", i.e. external IdP adapter not enabled); tokens inserted by `scripts/seed-*.py` | No registration, login, IdP, or token issue/revoke API |
| **Merchant/Store** | Merchant & store master data | merchant, store | PARTIAL | `MerchantMapper.xml`, `StoreMapper.xml` (insert/find/list only) | No freeze/close/update, no onboarding |
| | Operator grants (STORE/MERCHANT scope, CATALOG permission) | store | PARTIAL | `StoreAccessService.java:21-44`, V17 CHECK | CATALOG is the only permission; ADMIN is a tenant-wide superuser |
| **Catalog** | SPU/SKU, spec templates, revisions, categories (≤3 levels), barcodes, images | catalog | MATURE | `ProductOperationsService.java`, `CatalogMerchandisingService.java`, V18/V33 | Legacy `POST /admin/skus` bypasses SPU/templates |
| | Search | catalog | PARTIAL | `MerchandisingMapper.xml:24-26` (`LOCATE` + keyset) | No relevance ranking or index |
| | Scheduled batch publish/unpublish/reprice | catalog | MATURE | `CatalogJobService.java:34-40`, `CatalogSchedulingTest` | — |
| | Trusted channel price (WEB/MINI_APP from credential) | catalog | MATURE | V34, `CatalogMapper.xml:9`, `QuoteService.java:48-49` | — |
| | Media upload | — | MISSING | `CatalogMerchandisingService.java:66` (URL only) | No file service |
| **Inventory** | Sellable quantity, per-order hold/confirm/release, return restock | inventory | PARTIAL | `InventoryMapper.xml:7,16`, `InventoryService.java:52-58` | No warehouse dimension, adjustment/stock-take, or stock ledger |
| **Marketing** | Rule DSL (bounded AST, 3-valued logic, trusted fields) | marketing | MATURE | `RuleEvaluator.java:29-56`, `Condition.java`, `RuleNode.java:14-40` | — |
| | Campaigns: versions, review, publish/pause, tiered/scoped offers | marketing-runtime | MATURE | `CampaignService.java:24-88` | No priority field; single best campaign; >100 candidates fails the quote |
| | Budget & funding split | marketing-runtime | PARTIAL | `BudgetMapper.xml:5-8`, V10 | Budget not returned on refund; per-version cap |
| | Static audiences + dynamic segments | marketing-runtime | PARTIAL | `SegmentService.java:24-89`, V20 | 100k processed-member cap; ~100 members/s per tenant |
| | Effect analytics (campaign/journey/coupon batch) | marketing-automation (insight) | PARTIAL | `EffectsMapper.xml`, `MarketingEffectsService.java:29` | Correlational only; no holdout/A-B |
| **Benefit** | Coupons: definition, wallet, hold/use/return, targeted batches, relative validity | benefit, automation | MATURE | `CouponService.java`, `CouponDeliveryService.java`, V9/V31 | Journey-granted coupons are not revoked on refund |
| | Internal entitlements with ledger & compensation debt | benefit | MATURE | `EntitlementService.java:25-46`, V11–V13 | No external provider (`ExternalEntitlementPort` unimplemented) |
| | Level benefit bundles, point-exchange offers | benefit | MATURE | `MemberBenefitService.java`, `PointOfferService.java` | — |
| **Automation** | Durable journeys (DAG, waits, decide, grant, notify, caps, lifecycle triggers) | marketing-automation | MATURE (engine) / PARTIAL (reach) | `JourneyService.java:56-265`, V14/V21/V32 | Notification is in-app only; triggers are a hard-coded `switch` |
| | Low-code ops pages (whitelist, preview, approval, rollback) | marketing-automation (ops) | MATURE (scoped) | `OpsPageService.java:27-76`, V15 | Lists are not filtered by store |
| **Trade** | Server-side quote (campaign vs coupon vs points, TTL, snapshot) | trade | MATURE | `QuoteService.java:30-102` | Stacking policy is hard-coded in trade |
| | Cart, address book, shipping fee, tax | — | **MISSING** | `Shop.tsx:52` (React state), no tables | — |
| **Order** | Order state machine incl. CLOSING, atomic multi-resource create | order, order-runtime | MATURE | `OrderLifecycle.java:21-42`, `OrderService.java:28-47` | No refund-reflecting state |
| | Unpaid-order timeout | order-runtime | **FRAGMENTED** | `OrderService.java:96-107` implemented, but **only** `POST /admin/orders/expire` calls it (`PaymentController.java:22`); not in `EventWorker.java:13` | Never runs automatically |
| **Payment** | Attempt, unknown-result handling, per-transaction reconcile | payment | PARTIAL | `PaymentService.java:19-76` | Sandbox only; no webhook; auto-check stops after 5 tries (~2 min) |
| | Refund, capacity guard | payment | PARTIAL | `RefundService.java`, V5/V6 | No FAILED state (`ck_refund_status` V5:68) |
| | Statement reconciliation, settlement, invoices, split payment | — | MISSING | grep: none | — |
| **Fulfillment** | One shipment per order, ship/deliver, aftersale block | fulfillment | PARTIAL | `FulfillmentService.java:19-45` | No split parcels or tracking; address never decrypted for WMS |
| **Aftersales** | Return/refund case, per-line exact allocation, restock, compensation | aftersales | MATURE (refund-only) | `AftersaleService.java:28-64` | No exchange, return window, inspection, or evidence attachments |
| **Governance** | Idempotent commands + audit row | platform-runtime | PARTIAL | `Commands.java:23-38`, V1:12-21 | Audit lacks target/outcome and has no query API |
| | Maker-checker / separation of duties | — | MISSING | same admin can submit+approve (`CampaignService.java:69`, `JourneyService.java:92`, `OpsPageService.java:42`) | — |
| **Operations** | Isolated-work visibility & manual retry (events, journeys, segments, jobs, deliveries) | several | PARTIAL | `EventDispatcher.java:45-47`, per-module retry endpoints | Tenant-scoped only; no global backlog view, no alerts |
| | Metrics / alerting / tracing / retention | — | MISSING | `application.yml:41-48`; no delete jobs | — |

**Platform candidates** (§17 of the prompt): recoverable batch jobs, the async worker runtime, audit, identity/authorization, notification.

---

## 4. Key Business Workflows

| Workflow | Start | Key States | Dependencies | Failure Handling | Weak Points |
|---|---|---|---|---|---|
| **Quote → Order** | `POST /v1/quotes`, `POST /v1/orders` | Quote consumed-once; Order `PENDING_PAYMENT` | member points, coupon, campaign budget, entitlement quota, inventory (all sync, one tx) | Any failure rolls back everything, including quote consumption; idempotent retry (`PersistedCommerceTest:183,525`) | Large transaction on hot rows (budget, SKU); lock-order inversion between create and release (INFERENCE, §11) |
| **Pay** | `POST /orders/{id}/payments` | `PAYMENT_IN_PROGRESS`; attempt `UNKNOWN→OPEN→PAID/CLOSED` | sandbox channel (outside tx) → outbox `payment.paid` → order consumer | Unknown never releases; amount/currency/tenant verified under lock; 5 auto-checks, then manual reconcile | No webhook; stops checking after ~2 min; an isolated `payment.paid` event stalls the order silently |
| **Cancel / timeout** | cancel API / admin expire | `CANCELLED` or `CLOSING` (if payment in flight) | same 5 resources | CLOSING keeps holds; money received beats cancel (`OrderLifecycle.java:33-37`) | **Expiry is never scheduled** → abandoned orders hold stock/coupons/budget/points indefinitely |
| **Fulfil** | outbox `order.paid`/`order.ready` → admin ship/deliver | `READY→SHIPPED→DELIVERED`; order `FULFILLING→COMPLETED` | sandbox WMS proof (outside tx) | Conditional CAS, blocked flag during aftersale | Single parcel; no auto-confirm receipt; fulfillment creation shares a tx with journeys/effects |
| **Return / refund** | `POST /aftersales` → approve → receive → refund | case `REQUESTED→WAIT_RETURN→REFUNDING→COMPLETED`; refund `UNKNOWN→SUCCEEDED` | inventory restock, payment refund capacity, points, coupon, entitlement, journeys | Over-refund guard (V6); exact cumulative allocation; compensation debt state for consumed entitlements | **No refund FAILED path**; order stays `PAID`; budget not returned; journey coupons not revoked |
| **Journey** | event trigger / lifecycle scan / manual enroll | instance `RUNNING/WAITING/ISOLATED/COMPLETED/CANCELLED/TIMED_OUT` | benefit/coupon grant in node tx; in-app notification | CAS checkpoint per node, SKIP LOCKED, backoff, isolate, deadline | Lifecycle scans do one member per tx (~4 members/s/journey); >10 matching journeys throws inside shared event tx |
| **Segment refresh** | schedule / manual | run `RUNNING→COMPLETED/FAILED/ISOLATED` | member facts | keyset batches of 100, checkpoint, atomic snapshot switch | Fails for tenants >100k members; uncaught tick loop |
| **Member lifecycle** | `order.completed` / `refund.succeeded` | growth ledger, cycle contributions, points lots | handler in app shell | net-contribution per source → replay-safe | Cycle `due` scan runs every second with no status index |
| **Catalog batch job** | operator job | `SCHEDULED→RUNNING→COMPLETED/ISOLATED/CANCELLED`, item CONFLICT | product ops API | per-item revision CAS, permission re-check per step | — (reference implementation) |

---

## 5. Architecture Pain Points

### P0 — blocks safe evolution

1. **Event consumers are not failure-isolated.** `EventDispatcher.pumpTenant` runs every handler for an event inside **one** transaction (`EventDispatcher.java:31-35`, verified). `order.paid.v1` is consumed by fulfillment, marketing effects, and journeys together. So:
   - A journey exception (e.g. >10 matching journeys, `JourneyService.java:161`) rolls back fulfillment creation.
   - After 5 failures (~30 s) the event is `ISOLATED` for **all** consumers.
   - No alert fires.

   Every roadmap item adds consumers to the same events (notifications, settlement, more journeys), so the blast radius grows with each feature. The per-consumer `platform_inbox` table already exists, which makes the fix cheap.
2. **Background workers are not failure-isolated.** Nine ticks run sequentially in one `@Scheduled` method on one thread (`EventWorker.java:13`, verified). `MemberCycleService.tick` and `MemberPointsService.tick` (verified) and the segment `due` loop (`SegmentService.java:62`) do not catch exceptions. Their due rows have no attempts or backoff, so a single poison row aborts the loop every second (INFERENCE). That starves every worker after it: coupon deliveries and catalog jobs, and journeys when segments fail. A slow channel re-query in `payments.tick` also delays event dispatch.

### P1 — materially increases engineering or operating cost

3. **Unpaid-order expiry is never automated.** `OrderService.expire` exists but is only reachable via admin `POST /admin/orders/expire`. No scheduler, UI, or script calls it (verified by grep). Abandoned orders keep inventory, coupons, budget, points, and entitlements reserved.
4. **Payment/refund long tail.**
   - Auto re-query gives up after 5 attempts with 4–64 s backoff (`PaymentMapper.xml:14-16`), and there is no webhook.
   - Refunds have only `UNKNOWN|SUCCEEDED` (`V5:68`, verified). A rejected refund leaves the case in `REFUNDING` and the refund capacity consumed, and `uk_refund_case` blocks reissue.
5. **No observability.** The app exposes only health, has no metrics or alerts, and puts traceId in error bodies but not in logs. The event list is metadata-only and tenant-scoped. Nothing can answer "what failed, since when, how many".
6. **Identity and authorization are dev-grade.**
   - Tokens are inserted by SQL seed scripts (some expire `2030-01-01`, `seed-local.py:20`).
   - There is no issuance or revocation API and no rate limiting.
   - Roles are ADMIN/MEMBER/OPERATOR. ADMIN is tenant-wide.
   - Any new endpoint defaults to MEMBER-accessible unless placed under `/v1/admin` (`SecurityConfiguration.java:27`, verified).
   - The same admin can submit and approve campaigns, journeys, and pages.
   - Coupon definitions, rule assets, segments, point offers, and coupon batches of up to 100k recipients publish without any approval.
7. **Hard-coded scale ceilings in the business path.**
   - A quote fails when a store has more than 100 published campaigns (`CampaignService.java:82`, verified).
   - Segments fail for tenants above 100k members, because the cap counts *processed* members, not matched ones (`SegmentService.java:85`, V20 CHECK).
   - Lifecycle scans process one member per transaction.
   - The cycle `due` scan has every member fall due at once at each period boundary.
8. **Shared-transaction coupling hides a saga.** Order create, payment fact, aftersale approve, and refund-succeeded each span 4–7 modules' tables in one MySQL transaction (`OrderService.java:28-47,83-90`). This is correct today and deliberate (`BACKEND_ARCHITECTURE.md` §一致性). It is also the single biggest obstacle to any later extraction.

### P2 — manageable but should be improved

9. **Lock-order inversion (INFERENCE).** Create locks budget → entitlement → stock. Confirm and release lock stock → coupon → budget → entitlement (`OrderService.java:35-37` vs `:62,88`). This is a deadlock risk on hot campaigns; MySQL will abort one side. No test covers it.
10. **Cross-domain process managers live in the app shell.** `MemberGrowthHandler`, `BenefitCompensationHandler`, `MemberBehaviorController` (business logic), `DashboardController` (aggregation). `ModuleBoundaryTest` does not scan `commerce-app`.
11. **Business rules scattered as hard-coded values.**
    - Quote TTL 300 s, pay deadline 900 s, 5 attempts, batch sizes 4/5/20, and CNY are hard-coded.
    - Coupon/campaign stacking lives in `trade` (`QuoteService.java:50-56`).
    - Lifecycle trigger predicates and the trigger→event map are Java `switch` statements (`JourneyService.java:152-158,248-259`). A new trigger needs an enum value, a CHECK migration, and a code change.
12. **No retention.** `platform_event/inbox/command/audit` grow forever. `order.closing.v1` and `order.fulfilling.v1` have no consumer and stay `PENDING`. `platform_command.response_json` may keep PII (UNKNOWN).
13. **Test pyramid is top-heavy.** 14 persisted modules plus `platform-runtime` have **0** tests of their own. All 19 behavior test classes are Spring Boot + MySQL tests in `commerce-app`, with workers disabled, so `EventWorker` wiring is untested.
14. **Code density.** Methods up to 1,324 characters on one line (`CatalogJobService.java:35`); `JourneyService` is 28.5 KB. The code is reviewable only by specialists. This raises the cost of onboarding and change.
15. **Undeclared Maven dependencies and gaps in `ModuleBoundaryTest`.** SQL/table access and `runtime.persistence` usage are unchecked.

### P3 — cleanup

16. `member_record` full `COUNT` per dashboard load; points wallet recomputed per ledger entry; browse events take the member row lock.
17. Coupon/entitlement expiry is computed at read time with no state transition, which is correct but invisible to reports.
18. Unused validation starter, dead `JourneyMapper.published`, and a hard-coded test schema name.
19. The legacy ACTIVE-SKU creation path bypasses SPU/template rules.

---

## 6. Missing Business Capabilities

Each gap is inferred from code or schema evidence, not from generic e-commerce checklists. "Direction" is a candidate, not an approved requirement.

| # | Gap | Evidence (why it appears missing) | Current workaround | Business impact | Technical impact | Recommended direction |
|---|---|---|---|---|---|---|
| G1 | **Automated unpaid-order expiry** | `expire` implemented but only admin-triggered (`PaymentController.java:22`); absent from `EventWorker` | Admin calls endpoint manually | Stock/coupons/budget/points stranded on abandoned orders | Hot rows stay held | Add `orders.tick()` to worker with its own isolation; config-driven deadline |
| G2 | **Payment long-tail & statement reconciliation** | No webhook (grep); auto-check capped at 5; no statement/file reconciliation | Manual `adminReconcile` | Late payments stuck in `PAYMENT_IN_PROGRESS/CLOSING`; no finance truth | Recovery depends on humans | Callback endpoint + signature verification; slow-lane re-query schedule; daily statement import with discrepancy queue |
| G3 | **Refund failure lifecycle** | `ck_refund_status IN ('UNKNOWN','SUCCEEDED')` | None | Rejected refunds stuck forever, capacity leaked | Invariant gap in payment state machine | Add `FAILED` + release capacity + reissue policy + aftersale `REFUND_FAILED` handling |
| G4 | **Order reflects post-sale outcome** | `OrderState` has no refunded/closed-after-refund; order stays `PAID` after pre-ship full refund | Read aftersale separately | Misleading order views and "paid since" facts (`OrderMapper.xml:10`) | Derived facts wrong | Add a read-model order status or explicit `REFUNDED`/`PARTIALLY_REFUNDED` projection; don't rewind history |
| G5 | **Marketing cost settlement on refund** | Budget only reserve/confirm/release (`CampaignFundingService`); journey coupons have no order link (`CouponApi.java:23`) | None | Budgets overstated; refunded orders keep marketing rewards | Compensation incomplete | Budget `REFUNDED` hold transition; link journey-granted coupons to source order |
| G6 | **Identity & account lifecycle** | No login/registration/IdP; tokens seeded by SQL | Seed scripts | Cannot onboard real users or staff | Security posture blocks exposure | IdP adapter (OIDC) → `Actor` mapping; token issue/revoke API; rate limiting |
| G7 | **Role/permission model & maker-checker** | Roles ADMIN/MEMBER/OPERATOR; CATALOG only permission; same admin approves own changes | Trust | No store-scoped staff (customer service, finance, marketing) | Default-allow for MEMBER on new paths | Permission catalog per resource; approver ≠ submitter; approval for coupon batches/rules/offers |
| G8 | **Audit trail usable by humans** | `platform_audit` has tenant/actor/operation/key only; no reader | DB queries | No accountability for money/benefit changes | — | Add target, outcome, reason; query API; exclude pumps from "silent" ops |
| G9 | **Cart, address book, shipping fee** | Cart is React state (`Shop.tsx:52`); address typed per order; no fee model | Re-enter per order | Conversion loss, no delivery pricing | Quote cannot price shipping | Persistent cart (member-owned), address book (encrypted), shipping template as quote component |
| G10 | **Real fulfillment integration** | Address is write-only ciphertext (no decrypt path); single parcel; sandbox WMS | Sandbox | Cannot ship real goods | Key rotation impossible (no key id) | Key-versioned cipher + audited decrypt for WMS adapter; parcel model when split shipment is needed |
| G11 | **Merchant settlement / invoicing** | Funding split computed (`QuoteService.java:81-83`) but never settled; no invoice tables | None | Only relevant if multi-merchant | — | Defer until multi-merchant is a confirmed business line |
| G12 | **Outbound notification & preferences** | NOTIFY writes `journey_notification` only | In-app list | Journeys cannot reach off-site users | — | One real channel first; templates, receipts, opt-out, quiet hours, global frequency cap |
| G13 | **Experimentation** | No holdout/control in journey or campaign model; report says "不代表因果提升" (does not represent causal lift) | Correlational reports | Cannot prove ROI | — | Holdout percentage on journey version; cohort compare already exists |
| G14 | **Merchant/store lifecycle** | Status column supports FROZEN but no API changes it | DB edit | Cannot suspend a store | — | Status transition commands + effect on catalog/quote |
| G15 | **Inventory operations** | Only `receive`; no adjustment, stock-take, ledger, warehouse | Receive more | Stock can only go up manually | — | Stock ledger + adjustment with reason; warehouse only when WMS lands |
| G16 | **Search relevance** | `LOCATE` substring over title | — | Poor discovery at catalog scale | Full scans | MySQL FULLTEXT/ngram first; external engine only with evidence |

Already closed since the 2026-09-23 exploration (FACT, V16–V34): member levels/cycles, points ledger, tags/behavior, SPU/spec templates/revisions, catalog search/jobs/channel price, dynamic segments, targeted coupon batches, lifecycle journey triggers, effect projections.

---

## 7. Domain / Module Boundary Analysis

Qualitative ratings; there is no project scoring standard.

| Module | Boundary | Data | Transaction | Load | Failure Isolation | Migration Cost | Extraction Recommendation |
|---|---|---|---|---|---|---|---|
| marketing (rule kernel) | Strong: pure, whitelisted JDK only | None | None | CPU-light | Pure function | Low | **Keep as library**; nothing to extract |
| order (state machine) | Strong: pure | None | None | — | — | Low | Keep as library |
| member | Clear API; many sub-capabilities | Clean owner; `member_level` 3 writers | Joins order/refund txs via handlers | Per-member row lock hot on bursts | Low | High | KEEP_MODULE; split internally (loyalty vs profile) |
| catalog | Clear | Clean | Mostly own tx; quote reads price sync | Read-heavy | Medium | Medium | KEEP_MODULE; first candidate for a read model if browse load grows |
| inventory | Clear API | Clean | Inside order tx | Hot SKU rows | None | High | KEEP_MODULE |
| trade | Orchestrator | `trade_quote` | Consumed in order tx | Every checkout | None | High | KEEP_MODULE |
| order-runtime | Central | Clean | Owns the multi-module tx | Every checkout | None | Very high | KEEP_MODULE |
| payment | Clear port (`PaymentChannel`) | Clean | Channel I/O already outside tx | External latency | Good (async re-query) | Medium | **Best future extraction candidate** (adapter/gateway), only when a real channel's latency/compliance demands it |
| fulfillment | Clear port (`WmsPort`) | Clean | Small | Low | Good | Medium | KEEP_MODULE; WMS adapter stays at the edge |
| aftersales | Orchestrator | Clean | Spans inventory/payment/member | Low | Low | High | KEEP_MODULE |
| marketing-runtime | Mixed: campaigns + segments + budgets | Two services write audience tables | Budget inside order tx | Segment scans heavy | Low | High | KEEP_MODULE; segment computation is the only compute-heavy piece |
| benefit | Clear | Clean | Inside order/journey tx | Medium | Low | High | KEEP_MODULE |
| marketing-automation | Three packages (journey/ops/insight) | `insight` reads journey tables by SQL | Node tx includes grants | Worker-heavy | Low (shared event tx) | Medium | KEEP_MODULE; **journey runtime** is a candidate for separate *worker deployment* (same code, workers-only instance) before any service split |

**Service extraction candidates today: 0.** The design doc's rule already requires measured load, team ownership, and failure-isolation evidence before any extraction (`BACKEND_ARCHITECTURE.md`, Future Service Map). None of that evidence exists: single author, no load data, one deployment.

---

## 8. Data Ownership & Consistency

| Data Asset | Current Owner | Writers | Readers | Boundary Risk | Recommendation |
|---|---|---|---|---|---|
| `platform_command`, `platform_audit` | platform-runtime | every command | none (no API) | Grows forever; may hold response PII | Retention policy; audit reader |
| `platform_event`, `platform_inbox` | platform-runtime | every module via `Outbox` | dispatcher | Unconsumed types stay PENDING; no archive | Mark unsubscribed types delivered-or-skipped; archive job |
| `platform_credential` | platform-runtime | seed scripts only | filter, `OperatorDirectory` | No lifecycle API; no `(tenant,actor)` index | Identity module ownership |
| `member_record.member_level` | member | admin create, growth apply, cycle assess | quote facts, journeys | Three writers coordinated by a boolean return | Single "level resolver" writer |
| `marketing_audience_snapshot/_member` | marketing-runtime | `AssetMapper` and `SegmentMapper` | campaign facts | Two services, same module | Merge ownership into segments |
| `journey_*`, `automation_coupon_*` | automation/journey | journey package | **insight package via SQL** (`EffectsMapper.xml:19-53`) | SQL coupling invisible to jdeps | Read via API or declare the projection |
| `order_record`, `member_behavior_event` indexes | order / member | **V32 (automation's migration) adds indexes** | — | Migrations centralized in app; ownership blurred | Tag migrations by owner; keep single Flyway history |
| `marketing_budget` | marketing-runtime | order tx (reserve/confirm/release) | reports | Hot row; not returned on refund | Add refund transition; consider sharded budget rows only after measuring |
| `order_record.address_cipher` | order-runtime | order create | **nobody** | Unreadable by fulfillment; single key, no id | Key-versioned cipher + audited decrypt port |

**Transactions (FACT).** Cross-module work is one local MySQL transaction with `Propagation.MANDATORY` participants. Remote I/O is kept outside transactions (`Propagation.NEVER` on sandbox channels). This works and is tested. It means the system is consistent by colocation, not by protocol. Hold/confirm/release tables already model TCC-style reservations, and they are the migration path if a split ever happens.

**Cache consistency.** Not applicable; there is no cache.

---

## 9. Rules / Workflow / State Model

| Area | Current representation | Assessment |
|---|---|---|
| Promotion eligibility | Bounded JSON AST → sealed Java tree, 3-valued logic, whitelisted trusted facts, versioned immutable assets snapshotted into campaigns | **Sufficient.** A rule engine (Drools) was explicitly rejected (`TECH_SELECTION.md:5`); the legacy Drools migration is `UNCHANGED_BLOCKED` (`docs/PROGRESS_STATE.json`). Do not add a rule engine |
| Promotion composition | Best single campaign by discount; coupon stack/exclusive; points last, all in `QuoteService` | Adequate now. Missing: priority/exclusion groups. Extract a small `PricingPolicy` in `marketing` **only** when a second composition rule is requested |
| Journey triggers | Enum + SQL CHECK + Java `switch` | Hard-coded but only 4 lifecycle triggers. A registry (trigger → fact query) is justified when the next trigger is added, not before |
| Order/payment/aftersale | Explicit state enums, CAS versions, DB CHECKs, per-transition events | Good. Gaps are *missing states* (refund FAILED, order refunded), not a missing framework |
| Journeys | Custom durable DAG runtime (≤32 nodes, CAS checkpoint, SKIP LOCKED, deadlines) | **Do not replace with a workflow engine.** Scale fixes (batched scans) are local |
| Approvals | Per-entity status fields (campaign, journey, page) | Fragmented. A shared approval component (submitter ≠ approver, reason, audit) is justified: 3 existing copies, 5 more entities need it (G7) |
| Config | Hard-coded constants | Move timeouts, batch sizes, and attempt limits to typed `@ConfigurationProperties`. No config center needed |

---

## 10. Integration Architecture

| Integration | Mode today | Keep sync? | Recommendation |
|---|---|---|---|
| Quote/order reservations | Sync, one tx | **Yes** | Correctness depends on atomicity |
| Payment channel ensure/observe | Sync call outside tx + async re-query | Mixed | Add callback (async) + slow-lane re-query; keep verification-under-lock |
| Payment fact → order | Outbox | Async | Keep, but isolate the consumer tx |
| Order paid → fulfillment / effects / journeys | Outbox, **shared consumer tx** | Async | Per-consumer transactions (P0-1) |
| Refund → aftersale → compensation | Outbox chain | Async | Add failure branch (G3) |
| WMS | Sandbox port, pre-tx | Sync request / async status later | Adapter + decrypt port |
| Notifications | None (in-app rows) | Async | Outbox-driven sender with receipts |

**Outbox properties (FACT).**
- At-least-once delivery.
- Producer dedup via `uk_event_fact`.
- Inbox per consumer.
- `SKIP LOCKED` claims, so multi-instance is safe.
- Backoff of 2^n s, isolated after 5 attempts.
- Audited manual retry.
- No per-aggregate ordering; handlers re-read authoritative state to compensate.

Throughput ceiling is about 20 events per second per instance (4 tenants × 5) (INFERENCE). A broker is **not** justified until measured backlog age says so.

---

## 11. Reliability / Performance / Security / Observability

| Dimension | Current maturity | Major gaps |
|---|---|---|
| **Reliability** | High in the data layer: idempotent commands, CAS, CHECKs, UNKNOWN-safe payments, durable checkpoints, crash-restart evidence (`docs/evidence/s9a/restart.json`) | Consumer/worker isolation (P0); expiry not scheduled; refund failure; single MySQL, no backup/restore drill (`architecture-risks.md`) |
| **Performance** | Bounded everywhere (keyset paging, batch caps, 64 KiB bodies, 10 s tx) | Hot budget/SKU rows in a 10+ statement tx; pool 8 vs 64 Tomcat threads; lifecycle scans ~4 members/s; segment ~100 members/s; cycle boundary thundering herd; `LOCATE` search. **No proven bottleneck**: no load data exists. Treat these as probable future bottlenecks and measure before optimizing |
| **Security** | Tenant from credential only; every SQL filters `tenant_id`; AES-GCM with AAD; read-only container; no scripts in rules/low-code | No IdP/login; long-lived seeded tokens; no rate limit; default-allow MEMBER on non-admin paths; no maker-checker; audit without target; app DB user has `GRANT ALL` and runs Flyway at boot; address key not rotatable |
| **Observability** | Health + traceId in errors + per-module ISOLATED lists | Cannot answer "what failed / blast radius / since when" without SQL. No metrics, alerts, MDC, or global backlog view |

---

## 12. Future Architecture Direction

Evolution drivers, ranked by evidence:

| Driver | Importance | Evidence |
|---|---|---|
| Going from local to real operation (identity, payment, WMS) | HIGH | README + `architecture-risks.md` explicitly defer these; sandboxes and ports exist |
| More marketing automation and consumers on the same events | HIGH | 9 consumers already; the last 12 commits mostly add consumers/triggers |
| Member/data growth (segments, cycles, scans) | MEDIUM | Hard caps (100k, 100 campaigns) and per-member loops |
| Reuse by a second application (capability platform) | MEDIUM | Confirmed product direction in `.engineering/exploration/CAPABILITY_GAPS.md` ("能力中台＋品牌自营商城", i.e. a shared capability middle-platform plus a brand-owned mall), but no second consumer exists |
| Multi-merchant settlement | LOW | Funding split exists; no merchant onboarding or settlement demand in code |
| High-concurrency flash sales | LOW (unproven) | No load data |
| AI/LLM integration | LOW | Nothing in code; no evidence of need |

```text
Current (local-complete modular monolith, sandbox edges)
  → Near-term: Stabilized monolith
      isolated consumers & workers, scheduled expiry, refund failure path,
      metrics + alerts, config properties, retention
  → Mid-term: Production-ready monolith
      IdP + permission model + maker-checker, real payment/WMS adapters,
      statement reconciliation, cart/address/shipping, outbound notification
  → Long-term (only if evidence appears): Selective separation
      workers-only deployment of the same jar; payment gateway adapter as a
      separate process if compliance/latency requires; shared capabilities
      exposed to a 2nd app via versioned API + app-level authorization
```

---

## 13. Evolution Roadmap

| Phase | Scope | Exit criteria |
|---|---|---|
| **Phase 0 — Stabilize** | (1) Per-consumer transaction in `EventDispatcher`, using the existing inbox. (2) Per-tick try/catch and attempts/backoff for cycle/points/segment due rows; split payments/refunds from other ticks. (3) Schedule `orders.expire`. (4) Refund `FAILED` + capacity release. (5) Micrometer metrics: backlog age, ISOLATED counts, UNKNOWN payments/refunds, stuck journeys, plus traceId in MDC. (6) `@ConfigurationProperties` for timeouts and batch sizes. (7) Consume or skip `order.closing/fulfilling`. | A failing journey cannot block fulfillment (integration test). A poison cycle row cannot stop catalog jobs (test). Abandoned orders auto-release (test with workers enabled). Alerts are defined for backlog age and ISOLATED>0 |
| **Phase 1 — Strengthen internal architecture** | Move `MemberGrowthHandler`/`BenefitCompensationHandler` into owning modules or an explicit `process` module. Extend `ModuleBoundaryTest` to commerce-app, SQL table prefixes, and declared-vs-used Maven deps. Give `member_level` a single writer. Unify audience table ownership. Add a shared approval component (submitter ≠ approver). Fix lock ordering between create and release. Add per-module unit tests for pure logic. | Boundary test covers the app and SQL. A deadlock test between create and release under concurrency passes. Maker-checker is enforced on campaign, journey, page, coupon batch, and rule |
| **Phase 2 — Missing business capabilities** | Identity (OIDC adapter, token lifecycle, rate limit) + permission catalog. Real payment adapter + callback + slow-lane re-query + daily statement reconciliation. Key-versioned address cipher + WMS decrypt port. Persistent cart, address book, shipping fee in quote. Budget/journey-coupon refund compensation. Order refund projection. One outbound notification channel with opt-out. | Real-channel sandbox contract tests pass (duplicate, out-of-order, timeout). A finance discrepancy report exists. A member can log in, keep a cart, and reuse an address |
| **Phase 3 — Platformize repeated capabilities** | Extract into `platform-runtime`: (a) a **recoverable batch job** framework (5 copies today: catalog jobs, segment runs, coupon deliveries, journey scans, events); (b) a **worker scheduler** with per-lane budgets; (c) approval + audit query; (d) notification. Journey trigger registry. | New batch/worker features reuse the framework with no copied backoff/isolate SQL |
| **Phase 4 — Service extraction (conditional)** | Only if measured: a workers-only deployment profile first (same jar). A payment-gateway process only if channel latency or compliance scope requires it. Before any split, replace the shared transaction with the existing hold/confirm/release protocol plus the outbox. | Load evidence, an owning team, and a failure-isolation benefit documented per candidate |
| **Phase 5 — Advanced (only if justified)** | Versioned external API for a second application (app-level credentials, quotas). Experimentation/holdouts. FULLTEXT or search engine by measured need. | A second consumer exists; holdout reports are used in decisions |

---

## 14. Priority Matrix

| Recommendation | Business Value | Technical Value | Urgency | Cost | Risk | Priority |
|---|---|---|---|---|---|---|
| Per-consumer event transactions | High | High | High | Low | Low | **P0** |
| Worker isolation (catch, backoff, lanes) | High | High | High | Low | Low | **P0** |
| Schedule unpaid-order expiry | High | Medium | High | Low | Low | **P1** |
| Refund FAILED path | High | Medium | High | Low–Med | Medium | **P1** |
| Metrics, alerts, MDC trace | High | High | High | Medium | Low | **P1** |
| Identity + token lifecycle + rate limit | High | High | Before exposure | Medium | Medium | **P1** |
| Maker-checker + approval for bulk benefits | High | Medium | Before real ops | Medium | Low | **P1** |
| Lift hard ceilings (100 campaigns, 100k segment cap, per-member scans) | Medium | Medium | Before growth | Medium | Medium | **P1** |
| Real payment adapter + callback + statement recon | High | Medium | Before real money | High | High | **P1** (timed with go-live) |
| Budget + journey-coupon refund compensation | Medium | Medium | Medium | Low | Low | **P2** |
| Order refund projection | Medium | Low | Medium | Low | Low | **P2** |
| Cart / address book / shipping fee | High | Low | Medium | Medium | Low | **P2** |
| Lock-order fix + deadlock test | Medium | High | Medium | Low | Medium | **P2** |
| Boundary test extension + app-shell handler relocation | Low | High | Medium | Low | Low | **P2** |
| Config properties for constants | Low | Medium | Medium | Low | Low | **P2** |
| Retention/archiving | Medium | Medium | Low | Medium | Medium | **P2** |
| Batch-job/worker platformization | Medium | High | Low | Medium | Medium | **P2** |
| Outbound notification channel | Medium | Low | Low | Medium | Medium | **P3** |
| Holdout experiments | Medium | Low | Low | Medium | Low | **P3** |
| Search FULLTEXT | Low | Low | Low | Low | Low | **P3** |
| Merchant settlement | Low (today) | Low | Low | High | High | **P3** (conditional) |

---

## 15. What Not To Do

| Tempting move | Why it's unjustified here |
|---|---|
| Split into microservices | No load data, one author, one deployment. Consistency relies on shared transactions across 4–7 modules; swapping calls for HTTP would silently break atomicity (the design doc says so). |
| Add Kafka/RabbitMQ | The outbox works. The ceiling (~20 events/s/instance) is unmeasured against real volume. The real problem is consumer isolation, which a broker doesn't fix by itself. |
| Add Redis for stock/budget | Would add a second source of truth for money-critical counters. The hot-row risk is unproven; measure first, and consider budget sharding in MySQL before a cache. |
| Adopt a workflow engine (Temporal/Camunda) | The custom journey runtime already provides durable waits, CAS checkpoints, and compensation. The gaps are scan throughput and triggers, not orchestration semantics. |
| Adopt a rule engine / migrate Drools | Explicitly rejected (`TECH_SELECTION.md:5`). The bounded AST with trusted facts is safer, and the migration is formally BLOCKED. |
| Build a "middle platform" before a second consumer exists | Reuse is a stated direction but unproven. Harden APIs and app-level auth first, then platformize what a real second app needs. |
| Event sourcing / CQRS | Append-only ledgers already exist where needed (points, entitlements, revisions). Full ES adds cost with no identified query/audit need it uniquely solves. |
| K8s/service mesh | Single container, local scope. Deployment topology isn't the constraint. |
| Distributed transactions (XA/Seata) | The existing hold/confirm/release tables are the right compensation protocol if a split ever happens. |
| Auto-refunding UNKNOWN payments to "clean up" | Violates the core invariant (unknown ≠ unpaid) that the design is built around. |

---

## 16. Interview / Resume Value

Only stories with code and test evidence are listed.

1. **"Timeout is not non-payment": the CLOSING state.**
   - *Problem:* a cancel or timeout during an unknown payment risks losing money or overselling.
   - *Decision:* an explicit `CLOSING` state where money received beats the cancel. Resources are held until a trusted fact arrives. Channel close and pay are both conditional, so exactly one wins.
   - *Evidence:* `OrderLifecycle.java:33-37`, `PaymentMapper.xml:12-13`, `PersistedCommerceTest:216,226,238`.
   - *Trade-off:* safe but not live; resources can stay held and recovery ends in manual reconcile.
   - *Likely questions:* why not a distributed lock? What if the channel never answers? How would you add webhooks without breaking the invariant?
2. **Atomic multi-resource checkout in a modular monolith.**
   - *Mechanism:* quote consumed once, points FIFO lots, coupon, budget cap, entitlement quota, and stock, each a conditional update inside one tx with `MANDATORY` propagation. jdeps enforces `.api` boundaries.
   - *Trade-off:* strong consistency with no saga, at the price of a large hot-row transaction and a harder future split.
   - *Follow-ups:* deadlock ordering, how you'd split, TCC mapping of the hold tables.
3. **Cent-exact money conservation.**
   - *Mechanism:* largest-remainder allocation per discount layer (`MarketingDecisionService.java:86-110`); a platform/merchant funding split with a DB CHECK; cumulative-difference partial refunds for cash and integer points (`AftersaleService.java:31-35`); an over-refund guard counting UNKNOWN refunds (V6/V7 additive migration + backfill).
4. **Durable journey engine without a workflow framework.**
   - *Mechanism:* one node per transaction with a CAS checkpoint, `SKIP LOCKED` claims, DB-time waits, deterministic source keys guarded by unique indexes, isolate → deadline → TIMED_OUT, and refund-driven cancellation (`JourneyService.java:178-221`; tests `PersistedCommerceTest:652,668`).
   - *Honest weakness to discuss:* the shared consumer transaction.
5. **Lot-based points with checkout holds, debt, and expiry relief.**
   - *Mechanism:* FIFO allocation over ≤200 lots, CHECK `remaining+held+expired<=credited`, refund to original lot (forfeit if expired), and debt offset by future credits.
   - *Evidence:* the concurrency test yields exactly {200, 409} (`PointsCheckoutTest:128-142`).
6. **Replay-safe loyalty reconciliation.** Net contribution per source under the original policy snapshot and order time, so duplicates, replays, and refund-before-complete all converge (`MemberGrowthService.java:56-74`, `MemberCycleService.java:84-134`).
7. **Safe rule DSL.** Three-valued logic: stale or missing facts evaluate to UNKNOWN and cannot be flipped by NOT. A bounded AST and trusted-field whitelist replace Drools (`RuleEvaluator.java:29-56`).

Not supported by evidence (do not claim): high QPS or TPS, distributed systems or microservices, Redis/MQ expertise from this project, multi-region, production traffic.

Also be ready to explain honestly: the project was built in 2 days by one author (git log), all numbers are local-test only, and payment, WMS, and IdP are sandboxes.

---

## 17. Unknowns

| Unknown | Why it matters |
|---|---|
| Real traffic, order volume, member count, campaign count | Decides whether hot rows, caps, and the ~20 events/s ceiling matter |
| Whether tests currently pass | Not executed in this read-only pass; last recorded evidence is in `docs/evidence/` and CI |
| Production deployment target, HA, backup/restore RPO/RTO | Single MySQL; no drill evidence |
| Real payment/WMS/IdP provider choice and contracts | Shapes callback, reconciliation, and decrypt design |
| Whether multi-merchant settlement is a real business line | Gates G11 |
| Whether a second application will consume the capabilities | Gates platformization (Phase 5) |
| Whether `platform_command.response_json` holds PII | Affects retention and compliance |
| Actual deadlock frequency under create/release contention | Lock-order inversion is inferred, not observed |
| Team ownership model | Single author today; extraction criteria depend on it |
| Behavior of uncommitted frontend changes | Excluded from judgment |
