# 实际执行结果

来源ignored.local/phase8-process/8cada456-recovery-results.json，无凭据，仅保留结构化验收指标。

```json
[
  {
    "scenario": "real_WAIT_kill_restart_two_JVM_race",
    "status": "PASS",
    "checkpoint_unchanged": true,
    "attempts": [
      1,
      1
    ],
    "committed_steps": 3,
    "grants": 1
  },
  {
    "scenario": "real_JVM_kill_after_effect_before_step_finish",
    "status": "PASS",
    "server_connection_terminated": true,
    "rolled_back_grants": 0,
    "rolled_back_steps": 0,
    "recovered_grants": 1,
    "ledger_entries": 1
  }
]
```
