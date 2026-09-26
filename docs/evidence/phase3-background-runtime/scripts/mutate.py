#!/usr/bin/env python3
"""第三阶段变异测试：逐个移除关键保护，运行针对性测试，期望失败；每个变异后恢复原文件并校验字节一致。"""
import hashlib, json, os, shutil, subprocess, sys, time
ROOT = '/Users/liruijun/personal/LLM/commerce-platform'
OUT = sys.argv[1]
M = [
 ('disable-tenant-quantum', 'platform-runtime', 'platform-runtime/src/main/java/com/lrj/commerce/runtime/TenantRotation.java',
  'public int limit(){return Math.max(0,Math.min(policy.quantum(),policy.maxItems()-items));}', 'public int limit(){return Math.max(0,policy.maxItems()-items);}',
  'TenantRotationTest,OrderExpiryLaneTest#hotTenantCannotStarveOtherTenants,MemberPointsLaneTest', True),
 ('disable-lane-fairness', None, 'commerce-app/src/main/java/com/lrj/commerce/app/EventWorker.java',
  'scheduler.setPoolSize(threads);', 'scheduler.setPoolSize(1);', 'EventWorkerLaneTest#slowLaneDoesNotStarveOtherLanes', True),
 ('treat-transient-as-permanent', 'platform-runtime', 'platform-runtime/src/main/java/com/lrj/commerce/runtime/FailureClass.java',
  'public boolean transientFailure(){return this==TRANSIENT||this==CONCURRENCY_RETRYABLE||this==DEPENDENCY_UNAVAILABLE;}', 'public boolean transientFailure(){return false;}',
  'EventFailureSemanticsTest#temporaryOutageNeverQuarantinesHealthyEvents,PaymentCheckLaneTest#channelOutageDoesNotExhaustAutomaticChecks', True),
 ('disable-inbox-dedup', 'platform-runtime', 'platform-runtime/src/main/java/com/lrj/commerce/runtime/EventDispatcher.java',
  'if(mapper.inbox(h.consumer(),event)==1)h.handle(event);', 'mapper.inbox(h.consumer(),event);h.handle(event);',
  'EventFailureSemanticsTest#businessRejectionQuarantinesWithCompleteEvidenceAndRetryRunsOnlyUnfinishedConsumers,EventConsumerIsolationTest', True),
 ('remove-skip-locked-events', 'platform-runtime', 'platform-runtime/src/main/resources/mappers/runtime/EventMapper.xml',
  "available_at&lt;=CURRENT_TIMESTAMP(3) FOR UPDATE SKIP LOCKED</select>", "available_at&lt;=CURRENT_TIMESTAMP(3) FOR UPDATE</select>",
  'EventSchedulingFairnessTest#concurrentWorkersNeverProcessAConsumerTwice', False),
 ('remove-skip-locked-expiry', 'order-runtime', 'order-runtime/src/main/resources/mappers/ordering/OrderMapper.xml',
  '<include refid="expiryDue"/> FOR UPDATE SKIP LOCKED</select>\n <update id="expiryFailed">', '<include refid="expiryDue"/> FOR UPDATE</select>\n <update id="expiryFailed">',
  'OrderExpiryLaneTest#concurrentInstancesExpireEachOrderExactlyOnce', False),
 ('remove-poison-retry-budget', 'platform-runtime', 'platform-runtime/src/main/java/com/lrj/commerce/runtime/RetryPolicy.java',
  'new RetryPolicy(Duration.ofSeconds(2),Duration.ofSeconds(16),0.25,5);', 'new RetryPolicy(Duration.ofSeconds(2),Duration.ofSeconds(16),0.25,1000);',
  'EventFailureSemanticsTest#businessRejectionQuarantinesWithCompleteEvidenceAndRetryRunsOnlyUnfinishedConsumers,OrderExpiryLaneTest#poisonOrderOnlyBlocksItselfAndIsQuarantinedWithEvidence', True),
 ('remove-per-order-failure-isolation', 'order-runtime', 'order-runtime/src/main/java/com/lrj/commerce/ordering/application/OrderService.java',
  'var type=FailureClass.of(failure);expiryFailed(tenant,check,type,failure);', 'if(failure!=null)throw failure;var type=FailureClass.of(failure);',
  'OrderExpiryLaneTest#poisonOrderOnlyBlocksItselfAndIsQuarantinedWithEvidence', True),
 ('remove-no-consumer-terminal', 'platform-runtime', 'platform-runtime/src/main/java/com/lrj/commerce/runtime/Outbox.java',
  'String reason=skipped.get(type);', 'String reason=null;', 'EventFailureSemanticsTest#noConsumerEventsHaveExplicitSemantics', True),
 ('open-platform-route-to-tenant-admin', None, 'commerce-app/src/main/java/com/lrj/commerce/app/SecurityConfiguration.java',
  '.requestMatchers("/v1/platform/**").hasAuthority("PLATFORM_OPERATOR")', '.requestMatchers("/v1/platform/**").hasAnyAuthority("PLATFORM_OPERATOR","ADMIN")',
  'PlatformRuntimeAuthorizationTest', True),
 ('remove-use-case-capability-check', None, 'commerce-app/src/main/java/com/lrj/commerce/app/BackgroundRuntime.java',
  'public View view(Actor actor){actor.require(Actor.Capability.EVENT_RUNTIME_METRICS_READ);return snapshot(Instant.now());}', 'public View view(Actor actor){return snapshot(Instant.now());}',
  'PlatformRuntimeAuthorizationTest#useCaseLayerRejectsTenantRolesEvenIfTheRouteWereOpened', True),
 ('retries-compete-in-fresh-lane', 'platform-runtime', 'platform-runtime/src/main/resources/mappers/runtime/EventMapper.xml',
  'AND attempts=0 AND transient_attempts=0 AND <include refid="types"/> GROUP BY tenant_id', 'AND <include refid="types"/> GROUP BY tenant_id',
  'EventFailureSemanticsTest#retryStormDoesNotDelayFreshEvents', True),
 ('disable-dependency-breaker', 'platform-runtime', 'platform-runtime/src/main/java/com/lrj/commerce/runtime/TenantRotation.java',
  'public static final int BREAKER_STREAK=3;', 'public static final int BREAKER_STREAK=Integer.MAX_VALUE;',
  'EventFailureSemanticsTest#dependencyOutageOpensTheBreakerInsteadOfBurningEveryEvent,PaymentCheckLaneTest#channelOutageAcrossTenantsOpensTheBreaker,TenantRotationTest', True),
]
env = dict(os.environ)
def sh(cmd, log):
    with open(log, 'a') as f:
        return subprocess.run(cmd, cwd=ROOT, shell=True, stdout=f, stderr=subprocess.STDOUT, env=env).returncode
def digest(p): return hashlib.sha256(open(os.path.join(ROOT, p), 'rb').read()).hexdigest()
results = []
only = set(sys.argv[2].split(',')) if len(sys.argv) > 2 else None
for name, module, path, old, new, tests, must_fail in M:
    if only and name not in only: continue
    full = os.path.join(ROOT, path); backup = full + '.mutation-backup'; before = digest(path)
    text = open(full).read()
    assert text.count(old) == 1, f'{name}: anchor not found exactly once'
    shutil.copy2(full, backup)
    log = f'{OUT}/mutation-{name}.log'
    try:
        open(full, 'w').write(text.replace(old, new)); os.utime(full, None)
        started = time.time()
        if module: sh(f'mvn -B -o -q install -DskipTests -pl {module}', log)
        code = sh(f"mvn -B -o test -pl commerce-app -Dtest='{tests}' -Dsurefire.failIfNoSpecifiedTests=false", log)
        seconds = round(time.time() - started)
    finally:
        shutil.move(backup, full)
        # 恢复的文件必须比编译产物新：否则增量编译认为源码未变，变异后的class/资源会残留到后续运行。
        os.utime(full, None)
        if module: sh(f'mvn -B -o -q install -DskipTests -pl {module}', log)
        else: sh('mvn -B -o -q compile -pl commerce-app', log)
    assert digest(path) == before, f'{name}: restore failed'
    detected = code != 0
    summary = [l.strip() for l in open(log) if 'Tests run:' in l and 'Time elapsed' not in l][-1:]
    results.append({'mutation': name, 'file': path, 'tests': tests, 'expectedToBeDetected': must_fail, 'detected': detected, 'seconds': seconds, 'summary': summary})
    print(json.dumps(results[-1], ensure_ascii=False), flush=True)
json.dump(results, open(f'{OUT}/mutation-results.json', 'w'), ensure_ascii=False, indent=1)
