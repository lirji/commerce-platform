# Mutation / Negative Tests

- Harness: `scripts/mutate.py`. Each mutation replaces exactly one anchor in one production file, installs the module, and runs the targeted tests. It then restores the file from a backup and **asserts the SHA-256 matches the original**, reinstalling the module afterwards.
- Raw results: `mutation-results.json`. Per-mutation logs were kept in the session scratchpad and are not committed (size).

| # | Guarantee removed | Mutation | Targeted tests | Expected | Result |
|---|---|---|---|---|---|
| 1 | tenant rotation quantum | `Run.limit()` ignores the quantum | `TenantRotationTest`, `OrderExpiryLaneTest#hotTenant…`, `MemberPointsLaneTest` | fail | **detected** (4 failures) |
| 2 | lane fairness | scheduler pool size forced to 1 | `EventWorkerLaneTest#slowLane…` | fail | **detected** |
| 3 | transient ≠ permanent | `transientFailure()` returns false | `EventFailureSemanticsTest#temporaryOutage…`, `PaymentCheckLaneTest#channelOutage…` | fail | **detected** (2/2) |
| 4 | inbox dedup | handle without the inbox check | `EventFailureSemanticsTest#businessRejection…`, `EventConsumerIsolationTest` | fail | **detected** (3/3) |
| 5 | `SKIP LOCKED` on the event claim | plain `FOR UPDATE` | `EventSchedulingFairnessTest#concurrentWorkers…` | survive | survived: 1,000/1,000 exactly once. The inbox PK and the locked status re-check carry correctness; `SKIP LOCKED` avoids lock waits (throughput). |
| 6 | `SKIP LOCKED` on the order-expiry claim | plain `FOR UPDATE` | `OrderExpiryLaneTest#concurrentInstances…` | survive | survived: 400/400 exactly once. Correctness comes from the locked re-check of the due condition; `SKIP LOCKED` is a throughput property here. |
| 7 | poison retry budget | budget 5 → 1,000 | `EventFailureSemanticsTest#businessRejection…`, `OrderExpiryLaneTest#poisonOrder…` | fail | **detected** |
| 8 | per-item failure isolation (order expiry) | rethrow instead of per-order backoff | `OrderExpiryLaneTest#poisonOrder…` | fail | **detected** |
| 9 | no-consumer terminal handling | `Outbox` ignores declarations | `EventFailureSemanticsTest#noConsumer…` | fail | **detected** |
| 10 | platform route authorization | `/v1/platform/**` also admits `ADMIN` | `PlatformRuntimeAuthorizationTest` | fail | **detected**: tenant admin got 404 instead of 403 on a missing platform path (route disclosure). The use-case check still returned 403 on `/runtime`, which is the defense in depth working. |
| 11 | use-case capability check | `BackgroundRuntime.view` without `require` | `PlatformRuntimeAuthorizationTest#useCaseLayer…` | fail | **detected** |
| 12 | retry fairness | fresh lane without `attempts=0 AND transient_attempts=0` | `EventFailureSemanticsTest#retryStorm…` | fail | **detected** |
| 13 | dependency breaker | `BREAKER_STREAK = MAX_VALUE` | `EventFailureSemanticsTest#dependencyOutage…`, `PaymentCheckLaneTest#channelOutageAcrossTenants…`, `TenantRotationTest` | fail | **detected** (4 failures) |

**All 11 mutations that were expected to be caught were caught.**

## Harness defect found and corrected (run 1 invalid)

- **What happened.** The first run (`mutation-results-run1-invalid.json`) restored each file with `shutil.move` from a `copy2` backup, which **preserved the original modification time**. Maven's incremental compiler treated the restored source as older than the mutated class and did not recompile it. Mutated classes therefore survived into later runs, the installed jars and the packaged application.
- **Evidence:** the packaged jar contained `BREAKER_STREAK = 2147483647` (mutation 13).
- **Consequences:**
  1. Run 1's mutation 5 "detected" result was contaminated by mutation 4 (inbox dedup disabled), which was still compiled in. That produced the spurious duplicate handler call. 7 follow-up runs with only the `SKIP LOCKED` change showed `inbox = calls = 1000`.
  2. The first live runtime/outage and E2E runs used that jar. They were discarded and repeated on a clean build (`09-runtime-verification.md`).
- **Fix.**
  - `os.utime` is now applied after every mutation and every restore.
  - For app-shell mutations the app module is recompiled after restore.
  - `mvn clean install` was run before the re-run.
  - The re-run is the table above (`mutation-results.json`). The restored-source SHA-256 checks passed in both runs; the defect was only in the build outputs.
