# P4.0 — Baseline and Invariants

- Date: 2026-09-26. Starting point: `main` @ `064d44b`. The Phase 1–3 work (R1, Phase 2, Phase 3) and the user's UI work were committed and merged earlier the same day as four capability branches (`chore/gitignore-cleanup`, `feat/background-runtime-lanes`, `feat/shop-storefront-ui`, `docs/runtime-evidence`). The working tree was clean when Phase 4 started.
- Environment:
  - Java 21, Maven 3.9.
  - MySQL 8.4 in Docker on macOS (`dev-infra-mysql84-1`, port 43306).
  - Isolated test schema `commerce_test_20260923`, taken from `.local/runtime.env`.
  - `commerce_local` was not touched.
- Authoritative inputs:
  - `docs/evidence/phase3-background-runtime/PHASE3_REPORT.md` (status `PHASE_3_COMPLETE_WITH_LIMITATIONS`);
  - its documents 02, 05, 06 and 12.

No whole-project rediscovery was done.

## 1. Backend baseline (`baseline-verify.log`)

Command: `scripts/verify.sh`, which runs `mvn -B verify`.

| Module | Run | Failed | Skipped |
|---|---|---|---|
| shared-kernel | 3 | 0 | 0 |
| marketing | 27 | 0 | 0 |
| order | 45 | 0 | 0 |
| commerce-app | 204 | 0 | 4 |
| architecture-tests | 3 | 0 | 0 |
| **Total** | **282** | **0** | **4** |

**BUILD SUCCESS: 278 passed, 0 failed, 4 skipped.** This is identical to the Phase 3 closing baseline. The 4 skipped tests are the opt-in benchmarks.

## 2. Architecture and governance tests

`ModuleBoundaryTest` (3 tests) passes in the baseline run. It checks compiled dependencies with jdeps: domains reach other domains only through `.api`, and the application shell reaches domains only through `.api` or `runtime`.

## 3. Browser baseline (`e2e/baseline-browser.log`, `e2e/baseline/browser-results.json`)

Setup, identical to Phase 3 (`scripts/browser-e2e.sh baseline`):
- the packaged jar runs on port 8606 against the test schema with workers on;
- fixtures come from `prepare-e2e.py`, `seed-member-suite.py --fresh` and `seed-operations.py --fresh`.

Result: **17 passed / 4 failed of 21.** The four failing specs are the same four as in Phases 2 and 3:

| Spec | Classification |
|---|---|
| `catalog-merchandising` | existing (Shop UI rework vs. spec) |
| `coupon-deliveries` | existing (spec text vs. UI) |
| `member-behavior` | existing (Shop UI rework vs. spec) |
| `member-cycles` | existing (`color-scheme` assertion vs. current styles) |

## 4. Working tree at start

- Clean on `main` @ `064d44b`.
- Nothing is staged or stashed by Phase 4.
- Phase 4 does not commit or push. The final report contains a commit boundary recommendation.

## 5. Recovery invariants established for Phase 4

These are the R1–R15 invariants from the Phase 4 brief. Each one is mapped to its evidence in `PHASE4_REPORT.md` §Recovery Invariants.

## 6. Inventory of retry / recovery / replay state before Phase 4

Taken from the source; see also Phase 3 document 02.

| Work | Retry state before Phase 4 | Operator recovery before Phase 4 | Audit detail before Phase 4 |
|---|---|---|---|
| events | `attempts`, `transient_attempts`, first/last failure time, class, `last_error` | `POST /admin/events/{id}/retry` (ISOLATED or SKIPPED → PENDING) | `platform_audit` only records operation and key (no target or state) |
| order expiry | `expiry_attempts`, `expiry_transient_attempts`, `expiry_retry_at`, `expiry_error` | `POST /admin/orders/{id}/expiry/retry` | same |
| payment/refund checks | `check_attempts`, `check_transient_failures`, `check_error` | manual reconcile (synchronous, idempotent query) | same |
| segments, journeys, deliveries, catalog jobs | per item `attempts` + ISOLATED status, bounded by business deadline | module `control` / `retry` commands | same |
| **points expiry** | **none**: a failing lot is re-selected every tick | none | – |
| **cycle assessment** | **none**: a failing member is re-selected every tick | none | – |
| replay of DELIVERED events | not possible: DELIVERED rows are never re-dispatched | none | – |
| retention | none: every table grows without bound | – | – |
