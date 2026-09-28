#!/usr/bin/env python3
"""Three bounded negative mutations; exact source bytes are restored in finally.

Run only while probe processes use an immutable jar. A failed build without the
expected assertion is rejected as an invalid mutation result. Always clean-build
again afterward; temporary binaries are not final delivery artifacts.
"""
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[4]
OUT = ROOT / '.local/phase8-mutation'
OUT.mkdir(parents=True, exist_ok=True)
SERVICE = ROOT / 'marketing-automation/src/main/java/com/lrj/commerce/journey/application/JourneyService.java'
GRAPH = ROOT / 'marketing-automation/src/main/java/com/lrj/commerce/journey/domain/JourneyGraph.java'
original = {path: path.read_bytes() for path in (SERVICE, GRAPH)}
BASE_TESTS = 'MoneyTest,MarketingDecisionTest,RuleEvaluatorTest,OrderLifecycleTest,ModuleBoundaryTest,'
mutations = [
    ('reversed_branch', GRAPH, 'case MATCH -> node.yesNext();', 'case MATCH -> node.noNext();',
        'PersistedCommerceTest#journeyHistoryRecordsWaitDecisionAndAcceptedActionWithStablePagination',
        'journeyHistoryRecordsWaitDecisionAndAcceptedActionWithStablePagination'),
    ('wait_due_lost', SERVICE, 'due = now.plusSeconds(node.seconds());', 'due = now;',
        'PersistedCommerceTest#journeyRequiresApprovalAndPersistsWaitBeforeExactlyOneNotification',
        'journeyRequiresApprovalAndPersistsWaitBeforeExactlyOneNotification'),
    ('stale_failure_guard_lost', SERVICE, 'current != null && current.version() == attempted.get().version()', 'current != null',
        'JourneyRecoveryTest#staleFailureCannotAttachToNodeAdvancedByAnotherWorker',
        'staleFailureCannotAttachToNodeAdvancedByAnotherWorker')]
result = []
try:
    for name, path, before, after, selection, expected in mutations:
        source = original[path].decode()
        assert source.count(before) == 1
        path.write_text(source.replace(before, after))
        log = OUT / (name + '.log')
        with log.open('w') as output:
            completed = subprocess.run(['bash', 'scripts/verify.sh', '-Dtest=' + BASE_TESTS + selection,
                '-Dsurefire.failIfNoSpecifiedTests=false'], cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
        content = log.read_text()
        caught = completed.returncode != 0 and expected in content and '<<< FAILURE!' in content and 'COMPILATION ERROR' not in content
        result.append(dict(mutation=name, detected=caught, expected_assertion=expected))
        path.write_bytes(original[path])
        print(name + ': ' + ('DETECTED' if caught else 'INVALID_RESULT'), flush=True)
        assert caught
finally:
    for path, content in original.items():
        path.write_bytes(content)
        assert hashlib.sha256(path.read_bytes()).digest() == hashlib.sha256(content).digest()
    (OUT / 'results.json').write_text(json.dumps(result, indent=2))
    print('Exact source restoration verified.', flush=True)
