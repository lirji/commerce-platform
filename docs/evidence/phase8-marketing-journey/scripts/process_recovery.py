#!/usr/bin/env python3
"""Real JVM kill, WAIT restart and two application process evidence.

A temporary trigger in the owned benchmark schema pauses AFTER benefit writes and
BEFORE step completion. It is always removed; no fault flag enters product code.
"""
import json
import os
os.environ['COMMERCE_PHASE8_PROBE_DB'] = 'commerce_phase8_recovery'
from concurrent.futures import ThreadPoolExecutor
from probe_support import *

END = dict(id='end', kind='END')
GRANT = dict(id='grant', kind='GRANT', next='end', benefit=dict(benefitId='credit', version=1))
results = []
trigger_created = False
try:
    prepare_database()
    a = launch(8628)
    tenant = fixture(8628, 'wait')
    publish(8628, tenant, [dict(id='wait', kind='WAIT', seconds=25, next='grant'), GRANT, END])
    instance = enroll(8628, tenant)
    pump(8628, tenant)
    before = history(8628, tenant, instance)
    assert before['instance']['status'] == 'WAITING' and len(before['steps']) == 1
    stop(a, force=True)
    a = launch(8628)
    b = launch(8629)
    after = history(8629, tenant, instance)
    assert before == after, 'Fixed graph and WAIT checkpoint survive a real JVM kill'
    assert pump(8628, tenant) == 0, 'Restarted process must not execute a future WAIT'
    wait_until(lambda: sql(f"SELECT due_at<=UTC_TIMESTAMP(3) FROM journey_instance WHERE tenant_id='{tenant}' AND instance_id='{instance}'") == '1', 40)
    with ThreadPoolExecutor(2) as pool:
        attempts = list(pool.map(lambda port: pump(port, tenant), (8628, 8629)))
    for _ in range(3):
        pump(8628, tenant)
    complete = history(8628, tenant, instance)
    assert complete['instance']['status'] == 'COMPLETED'
    assert sql(f"SELECT COUNT(*) FROM benefit_grant WHERE tenant_id='{tenant}'") == '1'
    assert len(complete['steps']) == 3
    results.append(dict(scenario='real_WAIT_kill_restart_two_JVM_race', status='PASS', checkpoint_unchanged=True,
        attempts=attempts, committed_steps=3, grants=1))
    print('Real WAIT restart and two JVM race passed.', flush=True)

    crash = fixture(8628, 'kill-effect')
    publish(8628, crash, [GRANT, END])
    instance = enroll(8628, crash)
    # Owned schema only, one owned tenant only, fixed 30-second pause in the stepFinish statement.
    sql(f"DELIMITER $$\nCREATE TRIGGER phase8_probe_pause BEFORE UPDATE ON journey_step_execution FOR EACH ROW BEGIN IF NEW.tenant_id='{crash}' AND NEW.node_kind='GRANT' THEN DO SLEEP(30); END IF; END$$\nDELIMITER ;")
    trigger_created = True
    with ThreadPoolExecutor(1) as pool:
        request = pool.submit(pump, 8628, crash)
        connection = [None]
        def sleeping():
            found = sql(f"SELECT ID FROM information_schema.PROCESSLIST WHERE DB='{DB}' AND STATE='User sleep' LIMIT 1")
            if found:
                connection[0] = int(found)
                return True
            return False
        wait_until(sleeping, 20)
        stop(a, force=True)
        sql('KILL CONNECTION ' + str(connection[0]))
        try:
            request.result(timeout=10)
        except (OSError, RuntimeError) as disconnected:
            # A killed HTTP process is expected to close the request without a response.
            request_termination = type(disconnected).__name__
    sql('DROP TRIGGER phase8_probe_pause;')
    trigger_created = False
    assert sql(f"SELECT COUNT(*) FROM benefit_grant WHERE tenant_id='{crash}'") == '0'
    assert sql(f"SELECT COUNT(*) FROM journey_step_execution WHERE tenant_id='{crash}'") == '0'
    assert history(8629, crash, instance)['instance']['version'] == 0
    for _ in range(3):
        pump(8629, crash)
    assert history(8629, crash, instance)['instance']['status'] == 'COMPLETED'
    assert sql(f"SELECT COUNT(*) FROM benefit_grant WHERE tenant_id='{crash}'") == '1'
    post(8629, '/v1/admin/events/pump', crash)
    assert sql(f"SELECT COUNT(*) FROM benefit_ledger WHERE tenant_id='{crash}' AND action='GRANT'") == '1'
    results.append(dict(scenario='real_JVM_kill_after_effect_before_step_finish', status='PASS',
        server_connection_terminated=True, rolled_back_grants=0, rolled_back_steps=0, recovered_grants=1, ledger_entries=1))
    print('Real JVM kill in effect/completion window recovered exactly one grant and ledger.', flush=True)
finally:
    if trigger_created:
        sql('DROP TRIGGER phase8_probe_pause;')
    cleanup()
    (OUT / f'{RUN}-recovery-results.json').write_text(json.dumps(results, indent=2))
    print(json.dumps(results, indent=2))
