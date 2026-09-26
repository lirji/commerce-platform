# B2 — Cross-Tenant Runtime Metrics Authorization

Status: **DECIDED and IMPLEMENTED** (engineering and security decision from repository evidence).

## 1. Evidence about the current model

- Authentication: `SecurityConfiguration.TokenFilter` resolves a Bearer token to an `Actor(tenantId, actorId, role, channel)` stored in `platform_credential` (hash only).
- Roles before Phase 3: `ADMIN`, `MEMBER`, `OPERATOR`. **Every role is tenant-scoped.** Every credential row has a non-null `tenant_id`. No platform identity existed.
- Authorization is layered:
  - The route layer uses namespaces: `/v1/admin/**` for ADMIN, `/v1/operations/**` for ADMIN/OPERATOR, an explicit member list, and `anyRequest().denyAll()`.
  - The use-case layer re-checks with `actor.requireAdmin()` and equality tests on the role.
- The RBAC model has no permission table. Roles are the only grant unit.
- Phase 2 exposed only a tenant-scoped health endpoint, `GET /v1/admin/events/health`, which reads `actor.tenantId()`. The global Micrometer meters were registered but not exposed (`management.endpoints.web.exposure.include: health`).

## 2. Options considered

| Option | Verdict |
|---|---|
| Reuse `ADMIN` for global metrics | **Rejected.** A tenant administrator would read every tenant's backlog. This is exactly the leak B2 must prevent. |
| Expose `/actuator/metrics` or Prometheus on the app port | Rejected. It needs its own authentication, and actuator tags can drift to high cardinality. |
| Separate management port (network boundary only) | Not chosen now. There is no deployment topology evidence (no ingress or sidecar definition) to rely on. It can be added later without changing the contract below. |
| **New platform role with a named capability** | **Chosen.** It fits the existing role-based filter, keeps least privilege, and is testable end to end. |

## 3. Decision

- **New role `PLATFORM_OPERATOR`.**
  - V36 extends the role CHECK constraint and widens `role` to `VARCHAR(32)`.
  - The role is not a tenant role. Every existing use-case check (`requireAdmin`, `role==MEMBER`, `role==OPERATOR`) rejects it, and the route filter only admits it to `/v1/platform/**`.
- **Named capability `EVENT_RUNTIME_METRICS_READ`.**
  - `Actor.capabilities()` derives it from `PLATFORM_OPERATOR` only.
  - The token filter adds capabilities as Spring authorities.
  - The use case `BackgroundRuntime.view(actor)` calls `actor.require(EVENT_RUNTIME_METRICS_READ)`.
  - The check therefore holds at **both** layers. Either layer alone still refuses tenant roles.
- **Endpoint `GET /v1/platform/runtime`.**
  - It returns global aggregates: event health and stats, and for each lane the rotation stats, backlog (due, oldest due age, quarantined) and schedule (start lag, run time).
  - It also returns the current alert codes.
  - It contains no tenant, order, event or member identifiers (tested).
- **The tenant health endpoint is unchanged.** `GET /v1/admin/events/health` still reads only `actor.tenantId()`. Query parameters cannot select another tenant (tested).
- **Metrics stay low-cardinality.**
  - `commerce.events.*` has no tags.
  - `commerce.lanes.*` has exactly one tag, `lane`, from a fixed set of 10 names (tested).
  - There is no tenant, order, event or user label.
- **A platform credential's `tenant_id`** is only the owning scope of the credential row (NOT NULL). Platform endpoints never read data by it.

## 4. Security contract (tested in `PlatformRuntimeAuthorizationTest` and `AuthorizationCoverageTest`)

| Caller | `GET /v1/platform/runtime` | `GET /v1/platform/<missing>` | Tenant endpoints |
|---|---|---|---|
| anonymous | 401 | 401 | 401 |
| tenant MEMBER | 403 | 403 | own member paths |
| tenant OPERATOR | 403 | 403 | own operations paths |
| tenant ADMIN | **403** | **403** (no route disclosure) | own admin paths; `/admin/events/health` is own tenant only, `?tenant=` ignored |
| PLATFORM_OPERATOR | **200** | **404** (permitted namespace) | **403** on `/v1/admin/**`, `/v1/me`, `/v1/orders`, `/v1/operations/**`, `/v1/members/me/**` |
| any role on an unregistered path outside its namespaces | 403 | – | – |

The Phase 2 concealment rule is unchanged:
- A path outside every namespace the caller may enter returns 403 (401 when anonymous), whether or not the route exists.
- A missing path inside a permitted namespace returns 404.

## 5. Provisioning

- There is no admin UI for platform credentials, by design.
- A platform credential is inserted by the operator into `platform_credential` with `role='PLATFORM_OPERATOR'`, in the same way the existing seed scripts create credentials: store only the token hash and set an expiry.
- Creating platform credentials through the tenant admin API is intentionally impossible.
