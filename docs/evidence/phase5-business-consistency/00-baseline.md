# Phase 5 starting baseline

- Source: Phase 4 final report at `docs/evidence/phase4-runtime-recovery/PHASE4_REPORT.md`: 329 run, 324 passed, 0 failed, 5 optional skips; browser 17/21, four persistent frontend/spec mismatches. This is historical Phase 4 evidence.
- Current `main` had the later server package refactor before this phase. The current clean working tree was branched as `fix/phase5-business-consistency` before edits.
- Current baseline command: `./scripts/verify.sh`, 2026-09-27. Result: `BUILD SUCCESS`; `commerce-app` 251 run, 0 failed, 0 errors, 5 skipped; `architecture-tests` 3 run, 0 failed. The five skips are opt-in benchmarks/profiler, not business tests. Full log: `/tmp/phase5-baseline-verify.log` (local, not committed).
- The difference in count from Phase 4 is preserved as a baseline fact; this document does not treat the earlier count as current. All new Phase 5 tests run after this baseline.
- Browser historical baseline: 17/21 as recorded in Phase 4 final runs and the subsequent package-refactor `CODEX_PROGRESS.md`. A fresh Phase 5 browser result is recorded separately in the regression evidence.

## Fixture isolation

`PersistedCommerceTest` creates a random `t-<UUID>` tenant for each test and all business rows are tenant scoped. The browser fixture scripts `prepare-e2e.py`, `seed-member-suite.py --fresh`, and `seed-operations.py --fresh` create new `browser-`, `member-suite-`, and `operations-` UUID tenants in the explicit `commerce_test_20260923` schema. The scripts reject other schemas. `OrderExpiryLaneTest` additionally deletes only its own generated `ox-<random>` prefix. Thus verification does not depend on earlier soak tenants. The historical fixture rows are retained; no shared-schema cleanup was attempted.
