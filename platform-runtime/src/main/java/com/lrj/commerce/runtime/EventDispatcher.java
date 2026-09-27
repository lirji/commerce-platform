package com.lrj.commerce.runtime;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.runtime.persistence.EventMapper;
import com.lrj.commerce.kernel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
/**
 * 每个消费者的Inbox与副作用独立成事务，失败只阻塞该消费者；全部成功时投递标记随最后一个消费者提交。
 * 调度契约：租户内按到期时间FIFO；每次访问租户最多quantum条；从未失败过的新到期事件的租户先访问但最多占半个预算；
 * 积压（含到期重试）按TenantRotation游标轮转直到本轮预算用完，因此任一有到期事件的租户最迟在一整圈轮转内被尝试。
 * 失败按FailureClass分两类预算：瞬时失败（依赖不可用、锁冲突、超时）走TRANSIENT退避且不计入毒事件次数，
 * 其余失败走POISON预算，第5次隔离；连续瞬时失败触发车道熔断，暂停领取而不是把健康事件耗成隔离。
 */
@Component
public class EventDispatcher {
    /** 非瞬时失败达到此次数后隔离，等待人工重放；与退避合计约30秒。 */
    public static final int MAX_ATTEMPTS=RetryPolicy.POISON.budget();
    /**
     * 调度预算：quantum为单次访问单租户的事件上限，tenantBatch为每次发现的租户数，
     * tick与maxAttempts共同限制单轮占用调度线程的时间和事件数，freshWindow内新到期的事件优先访问。
     */
    public record Budget(int quantum,int tenantBatch,Duration tick,int maxAttempts,Duration freshWindow) {
        public static final Budget DEFAULT=new Budget(5,50,Duration.ofMillis(1000),2000,Duration.ofSeconds(10));
        public Budget {if(quantum<1||tenantBatch<1||maxAttempts<2||tick.isNegative()||tick.isZero()||freshWindow.isNegative())throw new IllegalArgumentException("调度预算无效");}
    }
    /** 进程内累计与最近一次轮转观测，不含租户标识，可直接作为低基数指标。 */
    public record Stats(long ticks,long attempted,long delivered,long consumerFailures,long quarantined,long rotations,long lastRotationMillis,int lastRotationTenants,long latencyMillisTotal,long maxLatencyMillis,Instant lastTickAt,Instant lastDeliveredAt,long transientFailures,long breakerTrips,boolean breakerOpen) { }
    private final EventMapper mapper;private final List<EventHandler> handlers;private final Set<String> types;
    private final TransactionTemplate tx;private final Commands commands;private final Budget budget;private final TenantRotation rotation;
    private final LongAdder ticks=new LongAdder(),attempted=new LongAdder(),delivered=new LongAdder(),consumerFailures=new LongAdder(),transientFailures=new LongAdder(),quarantined=new LongAdder(),latencyMillis=new LongAdder();
    private final java.util.concurrent.atomic.LongAccumulator maxLatencyMillis=new java.util.concurrent.atomic.LongAccumulator(Math::max,0);
    private volatile Instant lastTickAt,lastDeliveredAt;
    private final EventHealthLog healthLog;
    /** 旧的单项重试接口写恢复审计；测试中手工构造的调度器没有审计组件时只执行状态变更。 */
    private RecoveryAudit audit;
    @Autowired(required=false) void audit(RecoveryAudit audit){this.audit=audit;}
    @Autowired
    public EventDispatcher(EventMapper mapper,List<EventHandler> handlers,PlatformTransactionManager manager,Commands commands,List<UnconsumedEventType> unconsumed) {this(mapper,handlers,manager,commands,Budget.DEFAULT,unconsumed);}
    public EventDispatcher(EventMapper mapper,List<EventHandler> handlers,PlatformTransactionManager manager,Commands commands) {this(mapper,handlers,manager,commands,Budget.DEFAULT,List.of());}
    public EventDispatcher(EventMapper mapper,List<EventHandler> handlers,PlatformTransactionManager manager,Commands commands,Budget budget) {this(mapper,handlers,manager,commands,budget,List.of());}
    public EventDispatcher(EventMapper mapper,List<EventHandler> handlers,PlatformTransactionManager manager,Commands commands,Budget budget,List<UnconsumedEventType> unconsumed) {
        this.mapper=mapper;this.handlers=List.copyOf(handlers);this.commands=commands;this.budget=budget;this.tx=new TransactionTemplate(manager);tx.setTimeout(10);
        rotation=new TenantRotation("events",new TenantRotation.Policy(budget.quantum(),budget.tenantBatch(),budget.tick(),budget.maxAttempts()));
        var names=new HashSet<String>();var all=new HashSet<String>();
        for(var h:handlers){if(!names.add(h.consumer()))throw new IllegalArgumentException("重复事件消费者");all.addAll(h.types());}types=Set.copyOf(all);
        // 声明无消费者的类型写入即跳过；同时存在消费者说明声明已过期，继续运行会让消费者永远收不到事件。
        for(var u:unconsumed)if(types.contains(u.type()))throw new IllegalStateException("事件类型已有消费者却声明为无消费者："+u.type());
        healthLog=new EventHealthLog(this);
    }
    public synchronized int tick() {
        if(types.isEmpty())return 0;
        ticks.increment();lastTickAt=Instant.now();var run=rotation.start();int count=0;
        // 从未失败过的新到期事件的租户先访问，避免历史积压推迟实时支付等事件；到期的重试走轮转，不与新事件抢优先阶段。
        if(!run.exhausted())for(var tenant:rotation.discover(run,()->mapper.freshTenants(types,budget.freshWindow().toMillis(),budget.tenantBatch()))) {
            if(run.halfExhausted())break;count+=rotation.visit(tenant,run,this::pumpTenant);
        }
        count+=rotation.rotate(run,(after,limit)->mapper.tenants(types,after,limit),this::pumpTenant);
        healthLog.maybeLog();
        return count;
    }
    /** 管理员只可触发自己租户的持久事件。 */
    public int pump(Actor actor) {actor.requireAdmin();return pumpTenant(actor.tenantId(),rotation.manual());}
    private int pumpTenant(String tenant,TenantRotation.Run run) {
        if(types.isEmpty())return 0;int count=0;
        for(var candidate:mapper.pending(tenant,types,budget.quantum())) {
            if(run.exhausted())break;
            var consumers=handlers.stream().filter(h->h.types().contains(candidate.eventType())).toList();
            boolean claimed=true,done=false;var failures=new StringJoiner(",");FailureClass failure=null;
            for(int i=0;i<consumers.size();i++) {
                var h=consumers.get(i);boolean finish=failures.length()==0&&i==consumers.size()-1;
                try {
                    // 每个消费者重新领取事件行锁；已有Inbox的消费者在重试时跳过，不重复产生效果。
                    Boolean locked=tx.execute(status->{
                        var event=mapper.lock(tenant,candidate.eventId());if(event==null)return false;
                        if(mapper.inbox(h.consumer(),event)==1)h.handle(event);
                        // 此前消费者全部成功时，投递标记随最后一个消费者提交，单消费者事件不增加事务数。
                        if(finish&&mapper.delivered(event.eventId())!=1)throw new IllegalStateException("事件投递版本冲突");
                        return true;
                    });
                    if(!Boolean.TRUE.equals(locked)){claimed=false;break;}
                    done=finish;
                } catch(RuntimeException error) {
                    // 只回滚该消费者事务，其他消费者继续；只保留消费者与异常类型，不保存异常文本避免载荷泄漏。
                    var type=FailureClass.of(error);failure=failure==null||failure.transientFailure()&&!type.transientFailure()?type:failure;
                    failures.add(h.consumer()+":"+errorType(error));consumerFailures.increment();
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("event retry id={} consumer={} failureClass={} errorType={}",candidate.eventId(),h.consumer(),type,errorType(error));
                }
            }
            if(!claimed)continue;
            run.attempted();attempted.increment();
            if(failure!=null) {
                recordFailure(candidate,failures.toString(),failure);
                // 瞬时失败说明依赖可能不可用：结束对该租户的本次访问，连续发生时由轮转熔断整个车道。
                if(run.failed(failure))break;
            } else if(done) {
                // 事件产生到最后一个消费者提交的端到端延迟，含排队与重试等待。
                var now=Instant.now();long latency=Math.max(0,Duration.between(candidate.createdAt(),now).toMillis());
                count++;delivered.increment();latencyMillis.add(latency);maxLatencyMillis.accumulate(latency);lastDeliveredAt=now;run.succeeded();
            }
        }return count;
    }
    /** 任一消费者非瞬时失败即按毒事件预算计数；全部为瞬时失败时只累加瞬时次数，退避更长且不隔离健康事件。 */
    private void recordFailure(EventHandler.Event candidate,String evidence,FailureClass failure) {
        String error=evidence.length()>160?evidence.substring(0,160):evidence;boolean transientFailure=failure.transientFailure();
        var policy=transientFailure?RetryPolicy.TRANSIENT:RetryPolicy.POISON;int failures=(transientFailure?candidate.transientAttempts():candidate.attempts())+1;
        long delay=policy.delayMillis(failures,java.util.concurrent.ThreadLocalRandom.current().nextDouble());
        int changed=tx.execute(s->mapper.failed(candidate.eventId(),delay,transientFailure,RetryPolicy.POISON.budget(),RetryPolicy.TRANSIENT.budget(),error,failure.name()));
        if(transientFailure)transientFailures.increment();
        if(changed==1&&policy.exhausted(failures)) {
            quarantined.increment();
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("event quarantined id={} type={} failureClass={} attempts={} transientAttempts={} lastError={}",candidate.eventId(),candidate.eventType(),failure,candidate.attempts()+(transientFailure?0:1),candidate.transientAttempts()+(transientFailure?1:0),error);
        }
    }
    private static String errorType(RuntimeException failure) {
        return failure instanceof DomainException domain?failure.getClass().getSimpleName()+"/"+domain.code():failure.getClass().getSimpleName();
    }
    /** 运维查询只返回元信息与失败证据，不返回业务载荷或异常内容。 */
    public List<EventMapper.EventView> list(Actor actor,String after,int limit){actor.requireAdmin();Inputs.page(after,limit);return mapper.list(actor.tenantId(),after,limit);}
    /**
     * 隔离或跳过的事件需要显式命令和审计才重放：只重置状态、两类计数与到期时间，保留失败分类、首末失败时间和最后错误作为证据；
     * 重放只执行尚无Inbox的消费者。当前没有消费者的类型不能重放，否则只会变成永远无人处理的PENDING。
     */
    public int retry(Actor actor,String key,String id){actor.requireAdmin();actor.require(Actor.Capability.RUNTIME_RECOVERY_EXECUTE);Identifiers.require(id);return commands.run(actor,"event.retry",key,id,Integer.class,()->{
        var before=mapper.lockView(actor.tenantId(),id);var event=mapper.find(actor.tenantId(),id);if(event!=null&&!types.contains(event.eventType()))throw new DomainException(DomainException.Code.CONFLICT,"事件类型当前没有消费者");
        if(mapper.retry(actor.tenantId(),id)!=1)throw new DomainException(DomainException.Code.CONFLICT,"事件不在可重放状态");
        if(audit!=null)audit.record(actor,"event.retry",key,EventRecovery.WORK_TYPE,id,"RETRY",before.status(),"PENDING",before.failureClass(),null,RecoveryAudit.APPLIED,null);
        return 1;});}
    /** 该事件类型当前是否有消费者。 */
    public boolean consumes(String type){return types.contains(type);}
    /** 本租户积压诊断：只统计有消费者的事件类型，不含载荷；租户标识不进入指标标签。 */
    public EventMapper.Health health(Actor actor){actor.requireAdmin();return health(actor.tenantId());}
    /** 全局积压健康，tenant为空表示全部租户；无消费者且未声明的类型单独计为unrouted，不计入积压与最老年龄。 */
    public EventMapper.Health health(String tenant){return types.isEmpty()?new EventMapper.Health(0,0,0,0,null,0,null,0):mapper.health(tenant,types);}
    public Stats stats(){var r=rotation.stats();return new Stats(ticks.sum(),attempted.sum(),delivered.sum(),consumerFailures.sum(),quarantined.sum(),r.rotations(),r.lastRotationMillis(),r.lastRotationTenants(),latencyMillis.sum(),maxLatencyMillis.get(),lastTickAt,lastDeliveredAt,transientFailures.sum(),r.breakerTrips(),r.breakerOpen());}
    public TenantRotation.Stats rotationStats(){return rotation.stats();}
    public Budget budget(){return budget;}
}
