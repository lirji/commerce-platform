# P4.6 — Retention Decision Matrix

Starting proposal: `docs/evidence/phase3-background-runtime/12-retention-proposal.md`. Its durations (30 / 90 days) were **not** adopted as rules.

- No product or legal document in the repository sets a retention period.
- Phase 4 therefore implements the mechanism and its safety invariants, keeps every duration configurable, and ships with retention **disabled** (§4).

## 1. Decision matrix

Row counts are from the test schema at the end of Phase 4 (see §3). Growth is per business fact.

| Data | Business purpose | Replay dependency | Dedup dependency | Audit dependency | Debug value | Storage-growth risk | Legal/product decision required? | Recommended (configurable) retention | Purge safety condition |
|---|---|---|---|---|---|---|---|---|---|
| `platform_event` DELIVERED | trace of published facts; replay source | **yes**: replay reads DELIVERED events inside the replay window | its inbox rows are the dedup key, but a DELIVERED event can never run again (live retry refuses DELIVERED) | weak (the business tables are authoritative) | high for recent days | **high**: one row per state change | **yes**: replay/debug window | `commerce.retention.delivered-events` (≥ 7 d floor). Proposal to the owner: 30 d. | status DELIVERED, `available_at` and `created_at` older than the cutoff, **not inside an active (RUNNING or PAUSED) replay range**, inbox rows deleted in the same transaction |
| `platform_event` SKIPPED | audit of declared no-consumer or operator-skipped facts; can be recovered once a consumer exists | recovery (RETRY of SKIPPED) | none, unless operator-skipped after a partial success; those inbox rows are deleted with the row | operator skips are also in `platform_recovery` | medium | low–medium | **yes** | `commerce.retention.skipped-events` (≥ 7 d). Proposal: 90 d. | status SKIPPED, older than the cutoff, inbox rows in the same transaction |
| `platform_event` ISOLATED | quarantine awaiting an operator decision | recovery | **yes**: its inbox rows prevent re-running consumers that succeeded | yes | high | low (bounded by alerts) | – | **never automatically** | – |
| `platform_event` PENDING | unfinished work | – | **yes** | – | – | – | – | **never** | – |
| `platform_inbox` | exactly-once per (consumer, event) | replay `UNPROCESSED` depends on it | **yes** | – | low | **high**: one row per consumer per event | follows events | **only together with its terminal event**, never on its own | the event is terminal and deleted in the same transaction |
| `platform_command` (idempotent responses) | idempotent replay of client commands | – | **yes**: a purged key re-executes the command | `response_json` may contain business data (R1 finding) | medium | **high**: one per command | **yes** (client retry horizon, data protection) | `commerce.retention.commands` (≥ 30 d floor), **off unless configured** | the command is complete (`response_json` not null) and older than the cutoff |
| `platform_audit` | who ran which command | – | – | **yes** | – | high | **yes (legal)** | **not purged by this mechanism** | – |
| `platform_recovery` (operator recovery audit) | who recovered what, when, why, and the result | – | – | **yes** | high | low | **yes (legal/ops)** | **not purged** | – |
| `platform_replay` (jobs) | replay history and counters | – | – | yes | medium | very low | no | not purged (small) | – |
| `member_work_retry` | retry state of failing points/cycles items | – | – | quarantined rows are evidence | high | self-limiting: deleted on success | no | quarantined rows kept until recovered; others deleted on success | – |
| diagnostic failure evidence (`last_error`, `failure_class`, timestamps on work rows) | diagnosis | – | – | yes | high | none (columns of existing rows) | no | lives and dies with its row | – |

## 2. Archive vs delete

- **Decision: DELETE only; no archive subsystem.**
- Evidence:
  - no code path reads DELIVERED or SKIPPED events except replay and the paged admin list;
  - business history is authoritative in the domain tables (`order_record`, ledgers, `marketing_effect_order`, …);
  - there is no export or analytics consumer of `platform_event` in the repository.
- An archive would be speculative infrastructure.
- If the product decides that events must be exported before deletion (Phase 3, open question 3), that becomes an archive step before `deleteEvents`. The batch boundary already exists for it.

## 3. Current growth (test schema, end of Phase 4; local schema has similar proportions)

See `retention-growth.txt`. `platform_event`, `platform_inbox`, `platform_command` and `platform_audit` are the growing tables. The other runtime tables stay small.

## 4. Why disabled by default

- The brief: "Do not hard-code arbitrary legal/business assumptions" and "Do not expose dangerous zero-retention defaults".
- A default of 30 days would be exactly such an assumption.
- Retention is therefore opt-in:
  - `commerce.retention.enabled=true` together with at least one explicit duration;
  - every duration must be at or above its floor, or **startup fails** (`RetentionTest.retentionIsDisabledByDefaultAndRefusesDangerousConfiguration`).
- Until the owner decides, storage grows as before. `commerce.retention.lag{class}` stays `NaN`, and the platform view shows the oldest row age per class, so the growth is visible. This is recorded as a **deferred decision**, not a blocker for Phase 4: the mechanism is complete and tested.
