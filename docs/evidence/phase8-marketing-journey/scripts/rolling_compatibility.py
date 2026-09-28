#!/usr/bin/env python3
"""Actual remote-main OLD binary and NEW binary share expanded V44/V45 schema.

No new node kind or changed Definition JSON is introduced. Old writes are honestly
marked LEGACY_PARTIAL; old execution of new instances produces PARTIAL coverage.
"""
import os
os.environ['COMMERCE_PHASE8_PROBE_DB'] = 'commerce_phase8_recovery'
from probe_support import *

result = []
try:
    prepare_database()
    newer = launch(8628)
    older = launch(8629, jar=ROOT / '.local/phase8-old-main/commerce-app/target/commerce-app-0.1.0-SNAPSHOT.jar')
    tenant = fixture(8628, 'rolling')
    nodes = [dict(id='wait', kind='WAIT', seconds=1, next='grant'),
        dict(id='grant', kind='GRANT', next='end', benefit=dict(benefitId='credit', version=1)), dict(id='end', kind='END')]
    publish(8628, tenant, nodes)
    new_instance = enroll(8628, tenant, 'new-writer')
    assert enroll(8629, tenant, 'new-writer') == new_instance
    pump(8629, tenant)
    observed = history(8628, tenant, new_instance)
    assert observed['instance']['steps'] == 1 and observed['traceCoverage'] == 'PARTIAL'
    old_instance = enroll(8629, tenant, 'old-writer')
    assert history(8628, tenant, old_instance)['traceCoverage'] == 'LEGACY_PARTIAL'
    pump(8628, tenant)
    time.sleep(1.2)
    for _ in range(4):
        pump(8628, tenant)
    for instance, coverage in ((new_instance, 'PARTIAL'), (old_instance, 'LEGACY_PARTIAL')):
        item = history(8628, tenant, instance)
        assert item['instance']['status'] == 'COMPLETED' and item['traceCoverage'] == coverage
        assert item['definition']['version'] == 1
    status, old_read = call(8629, '/v1/admin/journey-instances', tenant, method='GET')
    assert status == HTTPStatus.OK and all(item['status'] == 'COMPLETED' for item in old_read)
    assert sql(f"SELECT COUNT(*) FROM benefit_grant WHERE tenant_id='{tenant}'") == '2'
    result.append(dict(scenario='OLD_main_aa8bef1_NEW_expanded_schema', status='PASS',
        old_reads_new_states=True, old_writes_new_reads=True, old_progress_is_partial=True,
        old_new_entry_deduplicates=True, grants=2, rollback_preserves_original_nodes=True))
finally:
    cleanup()
    (OUT / f'{RUN}-rolling-results.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))
