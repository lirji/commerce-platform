#!/usr/bin/env python3
"""第四阶段变异测试：逐个移除关键保护，运行针对性测试，期望失败；每个变异后恢复原文件并校验字节一致，
恢复后刷新mtime并重新安装该模块，防止第三阶段发现的“保留旧mtime导致增量编译残留变异类”问题。
用法: mutate.py <输出目录> [变异名,...]"""
import hashlib, json, os, shutil, subprocess, sys, time
ROOT = '/Users/liruijun/personal/LLM/commerce-platform'
OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)
T = 'commerce-app'
M = [
 # (名称, 需重装模块, 文件, 原文, 变异, 测试, 是否必须被检出)
 ('remove-points-item-retry-isolation', 'member', 'member/src/main/resources/mappers/member/PointsMapper.xml',
  "AND (r.item_id IS NULL OR (r.quarantined_at IS NULL AND r.retry_at&lt;=#{at}))\n  ORDER BY l.expires_at,l.lot_id LIMIT #{limit}</select>", "\n  ORDER BY l.expires_at,l.lot_id LIMIT #{limit}</select>",
  'ItemRetryIsolationTest#moreBadLotsThanOneQuantumNoLongerBlockTheTenant+badLotBacksOffAloneAndIsQuarantinedAfterFiveFailures', True),
 ('remove-cycles-item-retry-isolation', 'member', 'member/src/main/resources/mappers/member/CycleMapper.xml',
  'WHERE m.tenant_id=#{tenant} AND m.cycle_due_at&lt;=#{at} AND <include refid="notBlocked"/>\n', 'WHERE m.tenant_id=#{tenant} AND m.cycle_due_at&lt;=#{at}\n',
  'ItemRetryIsolationTest#badMembersDoNotBlockAssessmentOfHealthyMembers+poisonCycleTenantDoesNotDelayOtherTenants', True),
 ('count-transient-as-permanent-item', 'member', 'member/src/main/java/com/lrj/commerce/member/application/ItemRetries.java',
  'boolean transientFailure=type.transientFailure();', 'boolean transientFailure=false;',
  'ItemRetryIsolationTest#transientLockTimeoutDoesNotConsumeThePoisonBudget', True),
 ('keep-retry-row-after-success', 'member', 'member/src/main/java/com/lrj/commerce/member/application/MemberPointsService.java',
  'retries.cleared(ItemRetries.POINTS,tenant,lot.lotId());', ';',
  'ItemRetryIsolationTest#independentFailingLotsKeepTheirOwnRetryState', True),
 ('skip-policy-rollout', 'member', 'member/src/main/java/com/lrj/commerce/member/application/MemberCycleService.java',
  'if(!page.isEmpty())mapper.pullDue(tenant,from,page.getLast(),rollout.effectiveFrom());', ';',
  'CycleScheduleTest#newPolicyRollsOutInBoundedBatchesAndEveryMemberIsReassessed', True),
 ('cycles-probe-sorts-whole-tenant', 'member', 'member/src/main/resources/mappers/member/CycleMapper.xml',
  'ORDER BY m.tenant_id,m.cycle_due_at LIMIT 1)', 'ORDER BY m.cycle_due_at LIMIT 1)',
  'CycleScheduleTest#criticalDiscoveryQueriesKeepTheirIndexPlans', True),
 ('bypass-replay-hard-rule', 'platform-runtime', 'platform-runtime/src/main/java/com/lrj/commerce/runtime/ReplayGate.java',
  'for(var effect:safety.effects())if(NEVER.contains(effect))return Decision.deny("REPLAY_NOT_SUPPORTED",effect+"："+safety.evidence());', ';',
  'ReplayTest#safetyGateFailsClosed', True),
 ('bypass-gate-at-creation', 'platform-runtime', 'platform-runtime/src/main/java/com/lrj/commerce/runtime/EventReplay.java',
  'if(!gate.allowed()){blocked.increment();log.warn("REPLAY_BLOCKED consumer={} mode={} code={}"', 'if(false){blocked.increment();log.warn("REPLAY_BLOCKED consumer={} mode={} code={}"',
  'ReplayTest#sensitiveConsumersAreRejectedBeforeAnyWork', True),
 ('bypass-gate-at-execution', 'platform-runtime', 'platform-runtime/src/main/java/com/lrj/commerce/runtime/EventReplay.java',
  'if(!gate.allowed()){blocked.increment();log.warn("REPLAY_BLOCKED job={}', 'if(false){blocked.increment();log.warn("REPLAY_BLOCKED job={}',
  'ReplayTest#executionTimeGateStopsARunningJobWhenTheClassificationChanges', True),
 ('reexecute-processed-consumer-in-replay', 'platform-runtime', 'platform-runtime/src/main/java/com/lrj/commerce/runtime/EventReplay.java',
  'if(fresh||mode==ReplayGate.Mode.REPROCESS)', 'if(true)',
  'ReplayTest#unprocessedReplayRestoresTheProjectionExactlyOnce', True),
 ('remove-replay-live-yield', 'platform-runtime', 'platform-runtime/src/main/java/com/lrj/commerce/runtime/EventReplay.java',
  'if(mapper.liveDue(liveTypes,liveYield)>=liveYield){yielded.increment();return 0;}', ';',
  'ReplayTest#replayYieldsToLiveWork', True),
 ('remove-replay-job-lock', 'platform-runtime', 'platform-runtime/src/main/resources/mappers/runtime/ReplayMapper.xml',
  "WHERE tenant_id=#{tenant} AND job_id=#{id} FOR UPDATE</select>", "WHERE tenant_id=#{tenant} AND job_id=#{id}</select>",
  'ReplayTest#twoInstancesAdvanceOneJobWithoutDuplicateExecution', True),
 ('remove-recovery-authorization', 'platform-runtime', 'platform-runtime/src/main/java/com/lrj/commerce/runtime/RuntimeRecovery.java',
  'public Result recover(Actor actor,String key,Request input) {\n        actor.require(Actor.Capability.RUNTIME_RECOVERY_EXECUTE);', 'public Result recover(Actor actor,String key,Request input) {',
  'RuntimeRecoveryTest#recoveryAndReplayAuthorizationMatrix', True),
 ('remove-recovery-audit', 'platform-runtime', 'platform-runtime/src/main/java/com/lrj/commerce/runtime/RuntimeRecovery.java',
  'audit.record(actor,operation,key,work.workType(),id,action.name(),transition.previousState(),transition.newState(),transition.failureClass(),reason,RecoveryAudit.APPLIED,null);', ';',
  'RuntimeRecoveryTest#quarantinedLotRecoveryIsExplicitAuditedIdempotentAndPreservesEvidence', True),
 ('recovery-resolved-without-cas', 'member', 'member/src/main/java/com/lrj/commerce/member/application/ItemRetries.java',
  'var row=mapper.lock(tenant,lane,id);', 'var row=mapper.find(tenant,lane,id);',
  'MultiInstanceRecoveryTest#concurrentRecoveriesOfOneItemApplyExactlyOnceAndAreAllAudited', False),
 ('allow-retention-to-delete-pending', 'platform-runtime', 'platform-runtime/src/main/resources/mappers/runtime/RetentionMapper.xml',
  "<delete id=\"deleteEvents\">DELETE FROM platform_event WHERE status IN ('DELIVERED','SKIPPED') AND event_id IN", "<delete id=\"deleteEvents\">DELETE FROM platform_event WHERE event_id IN",
  'RetentionTest#onlyProvablySafeDataIsPurged', False),
 ('retention-selects-unfinished-events', 'platform-runtime', 'platform-runtime/src/main/resources/mappers/runtime/RetentionMapper.xml',
  "FORCE INDEX(ix_event_delivery) WHERE status=#{status} AND available_at&lt;#{cutoff}", "WHERE status IN (#{status},'PENDING','ISOLATED') AND available_at&lt;#{cutoff}",
  'RetentionTest#onlyProvablySafeDataIsPurged', True),
 ('delete-dedup-beyond-terminal-events', 'platform-runtime', 'platform-runtime/src/main/resources/mappers/runtime/RetentionMapper.xml',
  '<delete id="deleteInbox">DELETE FROM platform_inbox WHERE event_id IN', '<delete id="deleteInbox">DELETE FROM platform_inbox WHERE tenant_id IN (SELECT tenant_id FROM platform_event WHERE event_id IN',
  'RetentionTest#onlyProvablySafeDataIsPurged', True),
 ('retention-ignores-active-replay', 'platform-runtime', 'platform-runtime/src/main/java/com/lrj/commerce/runtime/RetentionLane.java',
  'if(replayFrom!=null&&replayFrom.isBefore(cutoff))cutoff=replayFrom;', ';',
  'RetentionTest#activeReplayRangesAreRetained', True),
 ('retention-zero-floor', 'platform-runtime', 'platform-runtime/src/main/java/com/lrj/commerce/runtime/RetentionLane.java',
  'public static final Duration EVENT_FLOOR=Duration.ofDays(7);', 'public static final Duration EVENT_FLOOR=Duration.ZERO;',
  'RetentionTest#retentionIsDisabledByDefaultAndRefusesDangerousConfiguration', True),
 ('event-recovery-without-row-lock', 'platform-runtime', 'platform-runtime/src/main/java/com/lrj/commerce/runtime/EventRecovery.java',
  'var event=mapper.lockView(tenant,id);if(event==null)return null;', 'var event=mapper.view(tenant,id);if(event==null)return null;',
  'MultiInstanceRecoveryTest#concurrentSkipAndRetryProduceOnlyValidTransitions', False),
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
    # 备份放在输出目录而不是源码树：源码树里的备份会被资源处理复制进target/classes（第四阶段首轮发现）。
    full = os.path.join(ROOT, path); backup = os.path.join(OUT, name + '.backup'); before = digest(path)
    text = open(full).read()
    assert text.count(old) == 1, f'{name}: anchor found {text.count(old)} times'
    shutil.copy2(full, backup)
    log = f'{OUT}/mutation-{name}.log'
    try:
        open(full, 'w').write(text.replace(old, new)); os.utime(full, None)
        started = time.time()
        build = sh(f'mvn -B -o -q install -DskipTests -pl {module}', log)
        code = sh(f"mvn -B -o test -pl {T} -Dtest='{tests}' -Dsurefire.failIfNoSpecifiedTests=false", log) if build == 0 else -1
        seconds = round(time.time() - started)
    finally:
        shutil.move(backup, full)
        os.utime(full, None)
        sh(f'mvn -B -o -q install -DskipTests -pl {module}', log)
    assert digest(path) == before, f'{name}: restore failed'
    detected = code != 0
    summary = [l.strip() for l in open(log) if 'Tests run:' in l and 'Time elapsed' not in l][-1:]
    results.append({'mutation': name, 'file': path, 'tests': tests, 'expectedToBeDetected': must_fail, 'detected': detected, 'buildFailed': code == -1, 'seconds': seconds, 'summary': summary})
    print(json.dumps(results[-1], ensure_ascii=False), flush=True)
    json.dump(results, open(f'{OUT}/mutation-results.json', 'w'), ensure_ascii=False, indent=1)
