# P4.8 — Operational SLO / SLI and Alert Codes

These are **internal operating objectives** for the background runtime. They are **not** customer SLAs, and no contractual promise is derived from them.
- Thresholds are the values the code evaluates today (Phase 2, Phase 3, plus the Phase 4 additions).
- Where the code has only one threshold, the critical level is an operator escalation rule, not a separate automatic code.

## 1. SLIs

| SLI | Source (metric / view) | Warning | Critical (escalate) | Notes |
|---|---|---|---|---|
| oldest pending age, events | `commerce.events.oldest.due.age`; view `events.health.oldestDueAgeSeconds` | > 300 s → `EVENT_BACKLOG_AGE` | > 30 min, or growing for 5 intervals (`EVENT_BACKLOG_GROWING`) | live delivery latency |
| oldest due age per lane | `commerce.lanes.backlog.oldest.age{lane}` | > 300 s → `LANE_BACKLOG_AGE:<lane>` | > 30 min for orders/payments/refunds (inventory and money) | points/cycles have backlog since Phase 4 |
| tenant rotation time | `commerce.events.rotation.last`, `commerce.lanes.rotation.last{lane}` | > 5 min → `*_ROTATION_SLOW` | > 15 min | a tenant's worst wait |
| lane scheduling delay | `commerce.lanes.start.lag{lane}` (12 lanes incl. replay, retention) | > 30 s → `LANE_STARVATION:<lane>` | > 2 min, or the scheduler stopped (external probe on `schedule.lastStartedAt`) | cross-lane isolation |
| success rate | `commerce.lanes.completed` / `commerce.lanes.items`; `commerce.events.delivered` / `attempted` | event failure rate > 20% of ≥ 20 attempts per minute → `EVENT_FAILURE_RATE` | sustained over 10 min | – |
| temporary failure rate | `commerce.lanes.transient.failures`, `commerce.events.transient.failures`, `commerce.events.retrying.transient` | breaker open → `*_DEPENDENCY_UNAVAILABLE` | breaker open > 5 min | expected during dependency outages; no item budget is consumed |
| permanent failure rate | `commerce.lanes.failures`, `commerce.events.consumer.failures` | rising together with quarantine growth | – | – |
| quarantine rate | `commerce.lanes.backlog.quarantined{lane}`, `commerce.events.quarantined` | any growth → `LANE_QUARANTINE_GROWTH:<lane>` / `EVENT_QUARANTINE_GROWTH` | stopped orders or payments > 0 for > 1 h (holds inventory or money) | recovery backlog |
| breaker-open duration | `commerce.lanes.breaker.open{lane}`, `commerce.events.breaker.open` | open | open across > 5 samples (5 min) | – |
| recovery backlog | quarantined counts above; `GET /v1/admin/runtime/stopped` per tenant | > 0 | > 0 older than 1 business day | operator work queue |
| replay backlog | `commerce.lanes.backlog.due{lane=replay}` (running jobs), `backlog.oldest.age{lane=replay}`, `commerce.replay.yielded` | a job running > 1 h | yield counter rising for > 30 min (live pressure is blocking replay) | replay is never urgent by design |
| replay safety | `commerce.replay.blocked` | any increase → `REPLAY_BLOCKED` | – | an attempted unsafe replay, or a classification change |
| retention backlog | `commerce.retention.lag{class}` | > 1 day → `RETENTION_LAG_HIGH` | > 7 days (storage risk) | only when enabled |
| retention failures | `commerce.retention.failures.consecutive` | ≥ 3 runs → `RETENTION_FAILURE` | ≥ 60 runs (≈ 5 min) | – |

## 2. Alert codes (fixed set; evaluated once per minute; published through `OperationalAlertPublisher`)

The default publisher writes `background runtime alert codes=[…]`. Event codes come from `EventHealthLog` (`event runtime alert codes=[…]`).

| Code | Meaning | Trigger | Clear condition | Severity | Operator response |
|---|---|---|---|---|---|
| `LANE_STARVATION:<lane>` | the lane is not getting scheduler time | start lag > 30 s, or idle > 30 s since its last finish | the lane runs on time | High | runbook §2 |
| `LANE_DEPENDENCY_UNAVAILABLE:<lane>` | dependency outage detected by the breaker | breaker open or tripped since the last sample | first success after the cool-down | High | runbook §3 |
| `LANE_BACKLOG_AGE:<lane>` | due work is waiting too long | oldest due > 300 s | below 300 s | Medium | runbook §1 |
| `LANE_QUARANTINE_GROWTH:<lane>` | new stopped items (now also points, cycles, replay: failed jobs) | quarantined count grew | no growth | Medium | runbook §4 |
| `LANE_ROTATION_SLOW:<lane>` | a tenant's worst wait > 5 min | last rotation > 300 s | faster rotation | Medium | runbook §1 |
| `EVENT_*` (Phase 2/3 set) | unchanged | – | – | – | Phase 3 runbook |
| `EVENT_NO_REQUIRED_CONSUMER` | PENDING events whose type has no consumer and no declaration | unrouted > 0 | unrouted = 0 | High | runbook §1 |
| **`REPLAY_BLOCKED`** (new) | the safety gate refused a replay (at creation, or a running job stopped) | `commerce.replay.blocked` increased | no further increase | Medium | runbook §5 |
| **`RETENTION_LAG_HIGH`** (new) | the oldest eligible row exceeds its retention by > 1 day | enabled and any class lag > 86,400 s | lag below 1 day | Medium (Low if storage is ample) | runbook §6 |
| **`RETENTION_FAILURE`** (new) | the cleanup keeps failing | ≥ 3 consecutive failed runs | a successful run | Medium | runbook §6 |

Codes considered and **not** added, because no runtime state backs them:
- `RECOVERY_FAILED`: a recovery is synchronous and returns per-item REJECTED results to the caller. A recovered item that fails again is re-quarantined and shows up as `LANE_QUARANTINE_GROWTH`.
- `RETRY_STORM`: retries are backoff-bounded and kept off the fresh event lane (Phase 3). The rate is visible through `EVENT_FAILURE_RATE` and the transient counters.

## 3. External alert provider boundary (§31)

- `OperationalAlertPublisher` (commerce-app) is the only exit for lane, replay and retention codes.
- The default bean logs.
- A provider (Slack, PagerDuty, …) is **not** implemented: the provider decision is still open. It would be a second bean that routes codes. `OperationalAlertTest` checks that codes pass through the interface, contain only fixed code and lane text, and that a failing publisher does not affect the scheduler.
- Event codes stay on the Phase 2 log line, and the operator view `/v1/platform/runtime` returns all codes.
