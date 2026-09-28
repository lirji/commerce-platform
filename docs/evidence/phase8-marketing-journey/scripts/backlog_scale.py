#!/usr/bin/env python3
"""Actual scheduler drain in owned MySQL: 100/1000/10000 END steps and 50000 future WAITs.

Load fixtures bypass entry commands for volume only; behavior/entry is validated
separately. The real Journey service still commits every END effect and trace.
Shared MySQL global counters are diagnostics, not per-process CPU accounting.
"""
import json
import math
from probe_support import *

results = []
END = dict(id='end', kind='END')


def seed(tenant, count, state='RUNNING'):
    for first in range(0, count, 500):
        values = ','.join(f"('{tenant}','i{i:06}','probe',1,'m1','e{i:06}','end','{state}',DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 2 DAY),0)"
            for i in range(first, min(first + 500, count)))
        sql('INSERT INTO journey_instance(tenant_id,instance_id,journey_id,journey_version,member_id,event_key,current_node,status,due_at,deadline,trace_origin_version) VALUES ' + values)


def pending(tenant):
    return int(sql(f"SELECT COUNT(*) FROM journey_instance WHERE tenant_id='{tenant}' AND status IN ('RUNNING','WAITING') AND due_at<=UTC_TIMESTAMP(3)"))


def counters():
    return dict((name, int(value)) for name, value in (line.split('\t') for line in sql("SHOW GLOBAL STATUS WHERE Variable_name IN ('Questions','Com_select','Com_insert','Com_update','Innodb_buffer_pool_reads','Threads_running')").splitlines()))


def percentile(values, fraction):
    return values[max(0, math.ceil(len(values) * fraction) - 1)]


try:
    prepare_database()
    app = launch(8630)
    future = fixture(8630, 'future', credit=False)
    publish(8630, future, [END])
    seed(future, 50000, 'WAITING')
    assert pump(8630, future) == 0
    # Read the actual full-row mapper shape and index plan, rather than a PK-only approximation.
    columns = 'instance_id,journey_id,journey_version,member_id,order_id,current_node,status,due_at,deadline,steps,attempts,result,version'
    branches = []
    for status in ('RUNNING', 'WAITING'):
        branches.append(f"(SELECT {columns} FROM journey_instance FORCE INDEX(idx_journey_due) WHERE tenant_id='{future}' AND status='{status}' AND due_at<=UTC_TIMESTAMP(3) ORDER BY due_at,instance_id LIMIT 5)")
    for status in ('RUNNING', 'WAITING', 'ISOLATED'):
        branches.append(f"(SELECT {columns} FROM journey_instance FORCE INDEX(idx_journey_deadline) WHERE tenant_id='{future}' AND status='{status}' AND deadline<=UTC_TIMESTAMP(3) ORDER BY due_at,instance_id LIMIT 5)")
    (OUT / f'{RUN}-full-due-explain.txt').write_text(sql('EXPLAIN ANALYZE SELECT * FROM (' + ' UNION '.join(branches) + ') c ORDER BY due_at,instance_id LIMIT 5'))
    (OUT / f'{RUN}-discovery-explain.txt').write_text(sql("EXPLAIN ANALYZE SELECT DISTINCT tenant_id FROM journey_instance FORCE INDEX(idx_journey_global_due) WHERE tenant_id>'' AND status IN ('RUNNING','WAITING') AND due_at<=UTC_TIMESTAMP(3) ORDER BY tenant_id LIMIT 50"))
    cohorts = {}
    for size in (100, 1000, 10000):
        tenant = fixture(8630, 'due-' + str(size), credit=False)
        publish(8630, tenant, [END])
        seed(tenant, size)
        cohorts[size] = tenant
    hot = fixture(8630, 'a-hot', credit=False)
    normal = fixture(8630, 'z-normal', credit=False)
    for tenant, size in ((hot, 5000), (normal, 5)):
        publish(8630, tenant, [END])
        seed(tenant, size)
    stop(app)
    app = launch(8630, workers=True)
    for size, tenant in cohorts.items():
        started = time.monotonic()
        before = counters()
        released = sql(f"SET @released=UTC_TIMESTAMP(3); UPDATE journey_instance SET due_at=@released WHERE tenant_id='{tenant}'; SELECT @released;")
        wait_until(lambda: pending(tenant) == 0, 900)
        elapsed = time.monotonic() - started
        after = counters()
        delays = sorted(float(line) for line in sql(f"SELECT TIMESTAMPDIFF(MICROSECOND,'{released}',completed_at)/1000 FROM journey_step_execution WHERE tenant_id='{tenant}'").splitlines())
        assert len(delays) == size
        assert sql(f"SELECT COUNT(*) FROM journey_effect WHERE tenant_id='{tenant}' AND kind='COMPLETED'") == str(size)
        row = dict(scenario='scheduler_due_drain', due=size, future_waits=50000, elapsed_seconds=round(elapsed, 3),
            steps_per_second=round(size / elapsed, 2), delay_p50_ms=percentile(delays, .5), delay_p95_ms=percentile(delays, .95),
            mysql_counter_delta={key: after[key] - before[key] for key in before if key != 'Threads_running'},
            observed_mysql_threads_running=after['Threads_running'])
        results.append(row)
        print(json.dumps(row), flush=True)

    post(8630, '/v1/admin/inventory/receipts', normal, dict(storeId='store1', skuId='sku1', quantity=5))
    status, quote = call(8630, '/v1/quotes', normal, dict(storeId='store1', items=[dict(skuId='sku1', quantity=1)]), role='member')
    assert status == HTTPStatus.OK
    status, order = call(8630, '/v1/orders', normal, dict(quoteId=quote['quoteId'], address=dict(recipient='负载测试', phone='13800000000', detail='隔离测试地址123')), role='member')
    assert status == HTTPStatus.OK
    status, payment = call(8630, '/v1/orders/' + order['orderId'] + '/payments', normal, role='member')
    assert status == HTTPStatus.OK
    started = time.monotonic()
    sql(f"UPDATE journey_instance SET due_at=UTC_TIMESTAMP(3) WHERE tenant_id IN ('{hot}','{normal}') AND status='RUNNING';")
    post(8630, '/v1/admin/sandbox/payments/' + payment['paymentId'] + '/fact', normal, dict(status='PAID'))
    wait_until(lambda: pending(normal) == 0, 30)
    normal_delay = time.monotonic() - started
    hot_when_normal_done = pending(hot)
    assert hot_when_normal_done > 0, 'Normal tenant must run before hot tenant finishes'
    wait_until(lambda: sql(f"SELECT status FROM order_record WHERE tenant_id='{normal}' AND order_id='{order['orderId']}'") == 'PAID', 30)
    wait_until(lambda: sql(f"SELECT COUNT(*) FROM platform_inbox i JOIN platform_event e ON e.event_id=i.event_id WHERE e.tenant_id='{normal}' AND e.event_type='order.paid.v1' AND i.consumer_id='fulfillment-order-v1'") == '1', 30)
    status, runtime = call(8630, '/v1/platform/runtime', normal, method='GET', role='platform')
    assert status == HTTPStatus.OK and runtime['lanes']['journeys']['backlog'] is not None
    results.append(dict(scenario='hot_tenant_and_payment_event_fairness', normal_delay_seconds=round(normal_delay, 3),
        hot_remaining_at_normal_completion=hot_when_normal_done, payment_and_fulfillment='PASS',
        schedules={name: runtime['lanes'][name]['schedule'] for name in ('journeys', 'payments', 'events')},
        journey_rotation=runtime['lanes']['journeys']['rotation'], journey_backlog=runtime['lanes']['journeys']['backlog']))
    print('Hot tenant, real payment reconciliation and event fulfillment progressed concurrently.', flush=True)
    wait_until(lambda: pending(hot) == 0, 500)
    assert sql(f"SELECT COUNT(*) FROM journey_instance WHERE tenant_id='{future}' AND status='WAITING' AND steps=0") == '50000'
    results.append(dict(scenario='future_WAIT_population', waiting=50000, executed=0, status='PASS'))
finally:
    cleanup()
    (OUT / f'{RUN}-scale-results.json').write_text(json.dumps(results, indent=2))
    print('Result artifact:', OUT / f'{RUN}-scale-results.json', flush=True)
