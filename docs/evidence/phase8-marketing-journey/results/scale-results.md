# 实际scheduler与fairness结果

```json
[
  {
    "scenario": "scheduler_due_drain",
    "due": 100,
    "future_waits": 50000,
    "elapsed_seconds": 5.088,
    "steps_per_second": 19.66,
    "delay_p50_ms": 2905.0,
    "delay_p95_ms": 4734.0,
    "mysql_counter_delta": {
      "Com_insert": 214,
      "Com_select": 1995,
      "Com_update": 215,
      "Innodb_buffer_pool_reads": 1,
      "Questions": 3090
    },
    "observed_mysql_threads_running": 2
  },
  {
    "scenario": "scheduler_due_drain",
    "due": 1000,
    "future_waits": 50000,
    "elapsed_seconds": 74.945,
    "steps_per_second": 13.34,
    "delay_p50_ms": 52137.0,
    "delay_p95_ms": 72985.0,
    "mysql_counter_delta": {
      "Com_insert": 2136,
      "Com_select": 21122,
      "Com_update": 2065,
      "Innodb_buffer_pool_reads": 156,
      "Questions": 32145
    },
    "observed_mysql_threads_running": 2
  },
  {
    "scenario": "scheduler_due_drain",
    "due": 10000,
    "future_waits": 50000,
    "elapsed_seconds": 469.99,
    "steps_per_second": 21.28,
    "delay_p50_ms": 235293.0,
    "delay_p95_ms": 446891.0,
    "mysql_counter_delta": {
      "Com_insert": 49840,
      "Com_select": 299769,
      "Com_update": 32843,
      "Innodb_buffer_pool_reads": 29655,
      "Questions": 478358
    },
    "observed_mysql_threads_running": 3
  },
  {
    "scenario": "hot_tenant_and_payment_event_fairness",
    "normal_delay_seconds": 0.726,
    "hot_remaining_at_normal_completion": 4991,
    "payment_and_fulfillment": "PASS",
    "schedules": {
      "journeys": {
        "lane": "journeys",
        "runs": 346,
        "failures": 0,
        "lastStartLagMillis": 3,
        "maxStartLagMillis": 15,
        "lastDurationMillis": 500,
        "maxDurationMillis": 30421,
        "lastStartedAt": "2026-09-28T00:27:05.964280Z",
        "lastFinishedAt": "2026-09-28T00:27:06.464408Z",
        "lastFailureClass": null
      },
      "payments": {
        "lane": "payments",
        "runs": 549,
        "failures": 0,
        "lastStartLagMillis": 3,
        "maxStartLagMillis": 61,
        "lastDurationMillis": 18,
        "maxDurationMillis": 25,
        "lastStartedAt": "2026-09-28T00:27:06.303730Z",
        "lastFinishedAt": "2026-09-28T00:27:06.322174Z",
        "lastFailureClass": null
      },
      "events": {
        "lane": "events",
        "runs": 549,
        "failures": 0,
        "lastStartLagMillis": 0,
        "maxStartLagMillis": 17,
        "lastDurationMillis": 88,
        "maxDurationMillis": 256,
        "lastStartedAt": "2026-09-28T00:27:07.050204Z",
        "lastFinishedAt": "2026-09-28T00:27:07.138233Z",
        "lastFailureClass": null
      }
    },
    "journey_rotation": {
      "lane": "journeys",
      "runs": 346,
      "items": 11137,
      "completed": 11136,
      "transientFailures": 0,
      "otherFailures": 1,
      "rotations": 2377,
      "lastRotationMillis": 92,
      "lastRotationTenants": 1,
      "breakerTrips": 0,
      "breakerOpen": false,
      "lastRunMillis": 500,
      "lastRunAt": "2026-09-28T00:27:05.964338Z"
    },
    "journey_backlog": {
      "due": 4975,
      "oldestDueAgeSeconds": 1,
      "quarantined": 0
    }
  },
  {
    "scenario": "future_WAIT_population",
    "waiting": 50000,
    "executed": 0,
    "status": "PASS"
  }
]
```

全局MySQL计数包含共享dev_infra上的并发测试，不是单进程DB负载。1000首轮受故障trigger元数据锁互扰，clean_recheck另证；journeys.maxDurationMillis=30421是同一测试互扰值，不能用500ms预算宣称硬超时。
