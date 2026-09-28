#!/usr/bin/env python3
"""Clean small/medium recheck after the original fault-trigger interference ended.

No reset/replay: new tenants and new instances, 50000 existing future rows remain.
Runs only after the original process probe has stopped and full regression ended.
"""
import json
import math
from probe_support import *

result = []
try:
    prepare_database()
    app = launch(8631, workers=True)
    assert not sql("SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA='commerce_phase8_bench' AND TRIGGER_NAME='phase8_probe_pause'")
    wait_until(lambda: sql("SELECT COUNT(*) FROM journey_instance WHERE tenant_id LIKE 'p8-%-a-hot' AND status='RUNNING'") == '0', 500)
    for size in (100, 1000):
        tenant = fixture(8631, 'warm-' + str(size), credit=False)
        publish(8631, tenant, [dict(id='end', kind='END')])
        for first in range(0, size, 500):
            values = ','.join(f"('{tenant}','i{i:06}','probe',1,'m1','e{i:06}','end',DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 2 DAY),0)" for i in range(first, min(first+500, size)))
            sql('INSERT INTO journey_instance(tenant_id,instance_id,journey_id,journey_version,member_id,event_key,current_node,due_at,deadline,trace_origin_version) VALUES ' + values)
        # Wait for the earlier process to exit; one scheduler process performs the measured drain.
        started = time.monotonic()
        released = sql(f"SET @released=UTC_TIMESTAMP(3); UPDATE journey_instance SET due_at=@released WHERE tenant_id='{tenant}'; SELECT @released;")
        wait_until(lambda: sql(f"SELECT COUNT(*) FROM journey_instance WHERE tenant_id='{tenant}' AND status='RUNNING'") == '0', 120)
        elapsed = time.monotonic() - started
        delays = sorted(float(line) for line in sql(f"SELECT TIMESTAMPDIFF(MICROSECOND,'{released}',completed_at)/1000 FROM journey_step_execution WHERE tenant_id='{tenant}'").splitlines())
        assert len(delays) == size
        result.append(dict(scenario='clean_recheck', due=size, elapsed_seconds=round(elapsed,3), steps_per_second=round(size/elapsed,2),
            delay_p50_ms=delays[math.ceil(size*.5)-1], delay_p95_ms=delays[math.ceil(size*.95)-1]))
        print(json.dumps(result[-1]), flush=True)
finally:
    cleanup()
    (OUT / f'{RUN}-warm-results.json').write_text(json.dumps(result, indent=2))
