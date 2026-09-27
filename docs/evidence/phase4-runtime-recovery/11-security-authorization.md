# P4.3/P4.5 — Recovery and Replay Authorization (§33–35)

## 1. Capabilities (existing model extended; no parallel authentication)

- Identity is still the Bearer credential, stored as a SHA-256 hash, resolved into an `Actor(tenant, actor, role)`.
- Phase 3 introduced `Actor.Capability` with a single platform capability. Phase 4 adds three **tenant-scoped** runtime capabilities.

| Capability | Granted to | Allows |
|---|---|---|
| `EVENT_RUNTIME_METRICS_READ` (Phase 3) | `PLATFORM_OPERATOR` | cross-tenant aggregate view `/v1/platform/runtime` (now also with `replay` and `retention` sections, still without tenant identifiers) |
| `RUNTIME_RECOVERY_READ` | tenant `ADMIN` | own tenant: work types, stopped work, recovery history, replay classifications, dry run, replay jobs |
| `RUNTIME_RECOVERY_EXECUTE` | tenant `ADMIN` | own tenant: recovery commands, including the legacy event and order-expiry retry endpoints (now checked explicitly) |
| `RUNTIME_REPLAY_EXECUTE` | tenant `ADMIN` | own tenant: create and control replay jobs |

- `OPERATOR` (store operator) and `MEMBER` have no runtime capability.
- `PLATFORM_OPERATOR` has **no** recovery or replay capability: there is no cross-tenant mutation path.
- **Retention administration is not exposed over HTTP.** It is deployment configuration only (08), so no `RUNTIME_RETENTION_ADMIN` endpoint exists to be misused.

## 2. Two layers (unchanged Phase 3 pattern)

1. Route:
   - `/v1/admin/**` requires the `ADMIN` authority;
   - `/v1/platform/**` requires `PLATFORM_OPERATOR`;
   - everything else not listed is `denyAll`.
2. Use case: `RuntimeRecovery`, `EventReplay`, `EventDispatcher.retry` and `OrderService.retryExpiry` call `actor.require(capability)`, which gives FORBIDDEN even when called directly without HTTP.

## 3. Tenant boundary (§34)

- The tenant always comes from the credential (`actor.tenantId()`). No request field can name another tenant.
- `RecoverableWork.find`, `stopped` and `recover` all filter by tenant.
- Tested in `RuntimeRecoveryTest.recoveryIsScopedToTheCallersTenant`:
  - another tenant's admin sees 0 stopped items;
  - their recovery of the foreign id is `NOT_STOPPED`;
  - the item is unchanged, and the rejection is audited in the caller's tenant.
- Replay jobs, dry runs and classifications operate on the caller's tenant only (the `platform_replay` PK includes the tenant).
- Tenant endpoints cannot mutate global runtime state: there is no endpoint that changes breakers, cursors, retention or other tenants.

## 4. 401 / 403 / 404 (§35) — `RuntimeRecoveryTest.recoveryAndReplayAuthorizationMatrix`

| Caller | `/v1/admin/runtime/*` (7 endpoints: stopped, recoveries GET/POST, dry-run, replays GET/POST, classifications) | `/v1/admin/runtime/nope` | `/v1/platform/runtime` |
|---|---|---|---|
| anonymous | **401** | 401 | 401 |
| MEMBER (tenant user) | **403** | 403 | 403 |
| OPERATOR (store operator) | **403** | 403 | 403 |
| PLATFORM_OPERATOR | **403** (unauthorized platform user for tenant recovery) | 403 | 200 |
| tenant ADMIN | **200** | **404** (missing path inside a permitted area) | **403** |

- Direct use-case calls: `recover` as `OPERATOR` → FORBIDDEN; `stopped` as `PLATFORM_OPERATOR` → FORBIDDEN.
- The Phase 3 tests still pass unchanged: `PlatformRuntimeAuthorizationTest` (B2) and `AuthorizationCoverageTest` (the member allow-list and default deny for every role).

## 5. Data exposure

- Stopped-work listings, recovery audit and replay jobs contain identifiers, states, failure class and exception type only. They contain no payloads, exception messages or credentials; the evidence strings are at most 160 characters.
- The platform view contains no tenant identifier (asserted in the matrix test).
- Metrics carry only fixed `lane` / `class` tags (`EventRuntimeObservabilityTest.laneMetricsAreTaggedOnlyByFixedLaneName`, extended to cover the retention tags).
