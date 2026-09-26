# Runtime Verification (packaged jar, workers on)

- Build: `mvn -Pwith-ui -DskipTests package` after the final clean `verify`. The jar contains the UI, and its `TenantRotation.BREAKER_STREAK` constant was checked to be 3 (no leftover mutation; see `10-mutation-tests.md`).
- Run: `COMMERCE_PORT=8606 COMMERCE_WORKERS_ENABLED=true COMMERCE_SANDBOX_ENABLED=true`. The database is the **test schema** reached through a local TCP proxy, `127.0.0.1:43307 → 43306` (`scripts/dbproxy.py`). The shared MySQL container was never paused or modified.
- `commerce_local` was not touched (it is still at V34).

## 1. Startup and topology

- Lane threads are `commerce-lane-1..3`. All 10 lanes started within 1 s of startup.
- Platform view at start (`runtime-platform-view-start.json`, PLATFORM_OPERATOR token):
  - no alerts;
  - maximum start lag per lane 3–40 ms;
  - maximum run times: events 1,015 ms, segments/journeys/points ≈ 510 ms (their budget), orders 37 ms, payments 214 ms;
  - existing test residue being processed: 451 due events, stopped checks (payments 295 and refunds 119, both from fixture residue).
- Graceful shutdown was observed on stop ("Graceful shutdown complete", no errors).

## 2. Live database outage (Scenario E): `runtime-db-outage.txt`

Procedure:
- Insert 300 healthy `member.registered.v1` events (30 tenants, real consumer `journey-order-paid-v1`) that become due 20 s after the cut.
- Kill the proxy for 80 s, then restore it.

| Observation | Result |
|---|---|
| Platform endpoint during the outage | HTTP 503 (credential lookup unavailable), as designed |
| Lane runs during the outage | each lane failed ~6 times in 80 s, classified `DEPENDENCY_UNAVAILABLE`; the breaker opened with cool-downs of 5, 10, 20 and 40 s |
| Events/items charged | **0**: all 300 events `attempts=0, transient_attempts=0`; no order, payment or refund transient counter changed |
| Quarantine | **0** events ISOLATED during the window |
| Alerts | `EVENT_DEPENDENCY_UNAVAILABLE`; `LANE_DEPENDENCY_UNAVAILABLE:<lane>` for the lanes whose breaker was open at the sample |
| Recovery | all 300 delivered **48 s after restore**. The delay is the remaining 40 s cool-down that was in flight when the DB came back. Every lane's breaker closed on its first successful run (`breakerOpen=false` 70 s after restore). |

Findings from earlier runs of this test (fixed before this final run):
1. **The event lane never tripped its breaker.** Its fresh-lane query ran outside `TenantRotation.rotate`, so the failure bypassed the breaker accounting. Fixed with `TenantRotation.discover(...)`; regression test `TenantRotationTest.eventFreshLaneDiscoveryFailureAlsoTripsTheBreaker`.
2. **Recovery latency depends on the cool-down cap.** With the original 5 min cap, a long outage could leave lanes (payments included) paused for up to 5 min after recovery. The cap was lowered to **1 min**, so recovery is ≤ 60 s. During an outage this costs 1 probe per lane per minute.
3. The first attempt at this test ran on a jar with leftover mutated classes. That was detected and discarded (`10-mutation-tests.md`).

After recovery, the per-minute health log showed `LANE_QUARANTINE_GROWTH:payments/refunds`. The cause is pre-existing fixture residue whose sandbox evidence conflicts: `check_error=BUSINESS_REJECTED:DomainException/CONFLICT` (18 payments, 4 refunds). None of them are `DEPENDENCY_UNAVAILABLE`. The alert behaved as specified.

## 3. Browser E2E on the same build

See `11-regression.md` §3: 17/21, with the same 4 pre-existing failures as Phase 2.
