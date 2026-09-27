# Phase 5 regression record

## Starting baseline

On 2026-09-27, before Phase 5 edits, `./scripts/verify.sh` passed: `commerce-app` 251 run / 0 failed / 5 skipped; `architecture-tests` 3 run / 0 failed. Historical Phase 4 browser baseline was 17/21 with four persistent spec/frontend failures; the current repository contains additional browser specs.

## Browser on the Phase 5 jar

`bash docs/evidence/phase5-business-consistency/scripts/browser-e2e.sh final-r1` used a clean packaged jar, workers enabled, and fresh UUID fixture tenants in `commerce_test_20260923`. Result: **20 passed / 4 failed of 24**. `operations.spec.ts` “旅程与效果” passed and no `/aftersales` 409 occurred. Browser JSON, log and screenshots are in `e2e/final-r1/` and its sibling logs.

The reviewable assertion summary is `e2e/final-r1-summary.md`. The raw JSON, logs and PNGs exist locally but match existing Git ignore rules; they are not implicitly part of a future commit.

| Failing spec | Classification | Basis |
|---|---|---|
| catalog-merchandising | `KNOWN_EXISTING` | Same failing assertion and error text as Phase 4 final-r3. |
| coupon-deliveries | `KNOWN_EXISTING` | Same expected `撤销 2` assertion versus displayed `撤销 / 保留 2 / 0`; UUIDs, timestamp and generated DOM IDs differ between isolated runs. |
| member-behavior | `KNOWN_EXISTING` | Same failing assertion and error text as Phase 4 final-r3. |
| member-cycles | `KNOWN_EXISTING` | Same failing dark-mode CSS assertion and error text as Phase 4 final-r3. |

These four failures were present before Phase 5 and are not attributed to its browser readiness change. There was no new deterministic browser regression. The revised browser spec does not change frontend product code.

## Clean source/package gate

The final `./scripts/build.sh` run passed on 2026-09-27: `npm ci`, frontend typecheck/build, `mvn -Pwith-ui clean verify`, and executable-jar UI check. `commerce-app`: **264 run, 0 failures, 0 errors, 5 opt-in benchmark/profiler skips**; architecture tests: **3 run, 0 failures**. It ran after every mutation was byte-restored and reinstalled. The five skips are reported as skipped, not passed. Product source and database migrations were not changed, so no new capacity or schema result is claimed. Local full log: `/tmp/phase5-clean-closure.log`.
