# P4.9 — Event Benchmark Regression Investigation (L7)

**Status: KNOWN_PERFORMANCE_TRADEOFF.** No code regression was found, and no code was changed.

All numbers are local measurements on the developer machine and are not production capacity. The setup is the same as Phase 3 (08): macOS, Docker MySQL 8.4, `innodb_flush_log_at_trx_commit=1`, 128 MB buffer pool, Java 21, Hikari pool 8, the shared test schema.

## 1. Reproduction (Phase 2 harness `EventSchedulingBenchmarkTest`, 10k aged backlog, 2 runs)

Raw data: `event-benchmark-p4-baseline-r1.jsonl`, `event-benchmark-p4-r2.jsonl`.

| Scenario | Phase 2 ticks / modeled | Phase 3 ticks / modeled | Phase 4 r1 | Phase 4 r2 | Victim / max first tick (P4) |
|---|---|---|---|---|---|
| single tenant 10k | 73 / 146 s | 47 / 94 s | 50 / 99.7 s | 50 / 100.0 s | – / 1 |
| balanced 20 × 500 | 65 / 130 s | 54 / 108 s | 54 / 107.8 s | 55 / 110.2 s | – / 1 |
| dominant 90% | 74 / 148 s | 48 / 96 s | 51 / 101.6 s | 49 / 97.3 s | next tick / 6 |
| **many small 2,500 × 4** | **44 / 88 s** | **50 / 99 s** | **49 / 97.7 s** | **48 / 95.7 s** | next tick / 48–49 |
| 300 events, 1 / 3 consumers | – | 3.6 / 7.7 s | 4.0 / 7.8 s | 3.5 / 9.1 s | – |

- The ~10% gap to Phase 2 for many-small reproduces: 48–49 vs 44 ticks.
- Phase 4 is within the run-to-run spread of Phase 3 (±5% here, ±15% reported in Phase 3).
- The two Phase 4 indexes on the delivery path (`ix_event_replay`, updated on the DELIVERED transition, and `ix_inbox_event`, on every inbox insert) show no measurable effect.
- The fairness properties are unchanged: the victim is served on the next tick, and every tenant is served within one rotation.

## 2. Profile (`EventDispatchProfileTest`, opt-in `-Dcommerce.event-profile=true`; `event-profile.jsonl`)

Every consumer call was timestamped. Gaps between consecutive calls are split into same-tenant (the per-event transaction) and cross-tenant (per-event transaction plus the tenant switch).

| Measurement | many-small 2500×4 (2 runs) | single 1×10000 (2 runs) | balanced 20×500 (2 runs) |
|---|---|---|---|
| events / tick | 200 | 200 | 182–189 |
| per-event transaction (same-tenant gap, median) | 4.05 ms | 4.27–4.28 ms | 4.12–4.17 ms |
| tenant-switch overhead (cross − same, median) | 0.99 ms | – | 1.70–1.78 ms |
| tenant switches | 2,536 | 0 | 2,022–2,027 |
| **switch share of wall time** | **5.0%** | 0% | 6.4–6.5% |
| rotation bookkeeping only (`TenantRotation` with no DB, 2,500 visits) | **2.8 µs per visit** (7 ms in total, 0.014% of wall time) | | |
| single autocommit INSERT round trip | 1.18 ms | | |

Breakdown of profiled factors:

| Factor (brief §28) | Finding |
|---|---|
| rotation overhead | 2.8 µs per visit, negligible |
| scheduler pool contention | not applicable: the benchmark drives one dispatcher directly on one thread |
| metrics / logging | LongAdder increments and a per-minute health log, inside the 2.8 µs |
| database queries | per tenant visit: one `pending(tenant, …)` query (≈ 1 ms round trip, the switch overhead); per 50 tenants: one discovery query |
| transaction count | one transaction per (consumer, event) = `SET autocommit=0`, lock `SELECT … FOR UPDATE SKIP LOCKED`, inbox `INSERT IGNORE`, `UPDATE … DELIVERED`, `COMMIT` (redo fsync), `SET autocommit=1` ≈ 4 ms. **This is ~95% of wall time in every scenario** and is the Phase 2 exactly-once design. |
| additional failure checks | none on the success path. Classification only runs on failure. |

## 3. Conclusion

- Per-event cost is identical across scenarios (≈ 4.1 ms). The only scenario-specific cost is the per-tenant `pending` query:
  - many-small needs one per 4 events;
  - single-tenant needs one per 5 events (its lone-tenant fast path);
  - Phase 2's algorithm also queried per tenant.
- The measurable fairness cost is ≤ 5% for many-small. The remainder of the historical 44-tick figure cannot be attributed to any code path. It is consistent with the run-to-run and data-growth variance measured in Phase 3: the Phase 2 run used a smaller schema, and the schema has grown by ~30k events and ~35k inbox rows since.
- The Phase 2 code state was never committed separately (R1, Phase 2 and Phase 3 landed in one commit), so a same-day A/B against Phase 2 is not possible.

**Low-risk fixes considered and rejected:**
- *Batch `pending` across tenants:* one query for N tenants. This would save ≤ 5% but couple tenants in one query result, reintroduce head-of-line ordering between tenants, and change the per-tenant fairness contract.
- *Multi-event transactions:* this would break per-event failure isolation (Phase 2/R1).
- *Hikari `auto-commit=false`* (saves two round trips per transaction): an application-wide transaction behaviour change and out of scope. It is recorded as a possible future throughput item, not a Phase 4 fix.

Per the brief (*do not weaken fairness to recover the benchmark*), the result is recorded as a **KNOWN_PERFORMANCE_TRADEOFF**: ≤ 5% for per-tenant fairness in many-small backlogs; the rest is environment variance.
