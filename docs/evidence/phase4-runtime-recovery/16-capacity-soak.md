# P4.8 — Live Restart, Soak and Capacity Model

Harness: `scripts/live-soak.py`. Raw data: `live/live-soak.json`, `live/live-soak.txt`, `live/app-*.log`.

Setup:
- the packaged jar was built with `clean package -Pwith-ui` from the restored sources after the mutation run;
- port 8606, test schema, workers on;
- the DB is reached through a local TCP proxy (43307 → 43306), so an outage never touches the shared MySQL container.

**Local measurements only. They are not production capacity.**

Two harness defects were found and fixed before the valid run:
1. The CTE recursion limit (1000) aborted the seeding. The partial fixture was removed.
2. The MySQL server's session time zone is UTC+8, so the fixtures must use `UTC_TIMESTAMP`. The app forces UTC sessions, and `CURRENT_TIMESTAMP` fixtures looked 8 hours in the future to the app.

The runs affected by these defects are not reported, and their fixtures were deleted.

## 1. Live `kill -9` during a backlog (C6 on the real process)

Seed:
- tenant `soak-8f4e9b5f`: 500 members, 2,000 expired point lots (10 points each), a cycle policy;
- all 500 members are due for assessment.

| Moment | Lots expired | Members assessed |
|---|---|---|
| `kill -9` (SIGKILL, no shutdown hook) | 213 | 184 |
| after restart and drain | **2,000** | **500** |

After restart:
- 0 lots with remaining points;
- **2,000 `EXPIRE` ledger rows, 0 lots with more than one** (the ledger has no unique key, so a duplicate would be visible);
- `SUM(expired)` = 20,000;
- 500 cycle accounts and 500 `member.cycle.assessed` events.

**No work was lost and nothing was duplicated.**

## 2. Bounded soak (600 s)

Running concurrently:
- ≈ 1 order per second through the real API: quote plus order, which produces `order.created` for the live event lane;
- 5 poison lots (a member that does not exist);
- a **30 s DB outage** at t = 181 s (proxy killed);
- a REPROCESS replay job for `marketing-effects-v1` over the last hour, at t ≈ 240 s;
- operator recovery of the quarantined poison lots, without fixing them, at t ≈ 300 s;
- retention enabled (`delivered-events=7d`) with 3,000 DELIVERED events 30 days old, plus their inbox rows.

| Risk (§38) | Observation (20 samples, every 30 s) | Verdict |
|---|---|---|
| retry accumulation | non-poison retry rows at the end: **0**; retrying events 0 | none |
| memory growth | G1 used heap cycles 46–220 MB (sawtooth) within a stable 312 MB committed heap; post-GC lows of 55, 46, 49, 80 and 86 MB show no upward trend | none observed in 10 min |
| thread starvation | threads 45–47 throughout; lane max start lag 24–26 ms before the outage; worst 8.8 s during the outage (a lane waiting for the 3 s connection timeout); `LANE_STARVATION` never fired | none |
| DB pool exhaustion | 4–5 app connections (pool 8), including during the replay and the retention purge | none |
| rotation drift | event due 0–1, oldest due age 0 s except 30 s during the outage | none |
| breaker instability | 8 breaker trips, all during the outage; `LANE_DEPENDENCY_UNAVAILABLE:<7 lanes>` raised during and cleared after; no trips afterwards | stable |
| retention backlog growth | 2,000 old events purged by the first sample and 3,000 (all of them) by t = 30 s, together with all 3,000 inbox rows; `RETENTION_LAG_HIGH` at t = 0, cleared after the purge | drains |
| duplicate execution | 568 orders → 568 `order.created` events, 568 delivered, 568 projection rows, **0 duplicate inbox rows**, 0 ISOLATED | none |

Other results:
- **Replay:** COMPLETED, examined 210, executed 210, failed 0, never yielded (live backlog below the threshold). It ran next to live traffic, and lane lags did not change.
- **Recovery:** 5 of 5 applied, with audit rows. The unfixed lots were quarantined again within about 30 s, as designed (recovery is not a fix). The poison lots never affected the other 568 orders' processing.
- **Outage:** 0 events quarantined, 0 items charged a poison attempt, and every lane resumed after the proxy returned.

## 3. Capacity model (measured locally; not a production guarantee)

| Parameter | Value | Source |
|---|---|---|
| scheduler threads | 3 (`commerce.worker-threads`, 1–6) | config |
| DB pool | 8; background ≤ 1 connection per thread → ≤ 3; observed 4–5 in total with HTTP | soak |
| lanes | 12: 10 business lanes + replay + retention; retention runs every 5 s | `EventWorker` |
| worst lane start lag bound | ⌈(12 − 1)/3⌉ × longest run ≈ 4 × ≤ 1 s | Phase 3 model with 12 lanes; measured ≤ 26 ms in normal operation |
| event per-consumer transaction | ≈ 4.1 ms (6 round trips + fsync) | 14 |
| event lane drain, one instance | ≈ 200 events/s processing, ≈ 100 events/s modeled with the 1 s fixed delay; 10k aged backlog in ≈ 96–110 s modeled | 14 |
| event lane, N instances | 1 / 2 / 4 instances: 124 / 188 / 337 events/s (0 duplicates) | Phase 3 P3.6 |
| order expiry | ≈ 9–13 ms per order (5 locks + outbox) | Phase 3 |
| points expiry, restart drain | 2,000 lots and 500 assessments drained after restart within the harness's wait loop (seconds, 200 items/s per lane budget) | §1 |
| retention | ≤ 2,000 rows per 5 s run → ≤ 400 rows/s per instance (3,000 old events + 3,000 inbox rows in < 30 s) | §2 |
| replay | ≤ 100 events per run (200 ms), 1 s delay → ≤ ~100 events/s per instance; it yields to live work | 06 |
| external dependency latency | payment and refund lanes spend at most 1 s per run; a slow channel only occupies its own thread (Phase 3: 2 s slow channel → other lanes ≤ 10 ms lag) | Phase 3 |
| scaling lever | **more application instances** (proven safe: SKIP LOCKED, inbox, CAS, row locks). Intra-lane parallelism stays disabled (§40 decision unchanged; nothing in Phase 4 showed horizontal scaling to be insufficient). | Phase 3 + 10 |
