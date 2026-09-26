# P3.0 — Baseline

- Date: 2026-09-26. Starting point: `main` @ `86d7e0a` + the uncommitted R1 and Phase 2 working tree (see `docs/evidence/phase2-event-runtime/PHASE2_REPORT.md` §13).
- Environment: Java 21, Maven 3.9, MySQL 8.4 in Docker on macOS (`dev-infra-mysql84-1`, port 43306). Isolated test schema `commerce_test_20260923` (from `.local/runtime.env`).
- Command: `scripts/verify.sh` (`mvn -B verify`). Log: `baseline-verify.log`.

## Result

| Module | Run | Failed | Skipped |
|---|---|---|---|
| shared-kernel | 3 | 0 | 0 |
| marketing | 27 | 0 | 0 |
| order | 45 | 0 | 0 |
| commerce-app | 166 | 0 | 1 |
| architecture-tests | 3 | 0 | 0 |
| **Total** | **244** | **0** | **1** |

**BUILD SUCCESS: 243 passed, 0 failed, 1 skipped.** This matches the Phase 2 closing baseline. The skipped test is the opt-in `EventSchedulingBenchmarkTest` (`-Dcommerce.event-benchmark=true`).

## Working tree at start

- Phase 2 and R1 changes are uncommitted (`git status` lists 29 modified and 18 untracked paths).
- User-owned changes that Phase 3 must not touch: `CODEX_PROGRESS.md`, `frontend/src/**`, `frontend/vite.preview-8602.config.ts`, `scripts/seed-shop-catalog.py`, `.engineering/exploration/`.
- The Phase 2 evidence under `docs/evidence/phase2-event-runtime/` is the authoritative starting point. No whole-project rediscovery was done.
