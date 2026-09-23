package com.lrj.commerce.journey.application;

import com.lrj.commerce.journey.api.JourneyApi;
import com.lrj.commerce.journey.infrastructure.JourneyMapper;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import com.lrj.commerce.member.api.MemberApi;
import com.lrj.commerce.store.api.StoreApi;
import com.lrj.commerce.benefit.api.EntitlementApi;
import com.lrj.commerce.aftersales.api.AftersaleApi;
import com.lrj.commerce.ordering.api.OrderApi;
import com.lrj.commerce.marketing.api.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

/** 单节点效果与检查点同事务，等待靠数据库时间点恢复，不占用睡眠线程。 */
@Service
public class JourneyService implements JourneyApi, EventHandler {
    private final JourneyMapper mapper;
    private final com.lrj.commerce.campaign.api.MarketingAssets assets;
    private final Commands commands;
    private final MemberApi members;
    private final com.lrj.commerce.member.api.MemberGrowthApi memberGrowth;
    private final StoreApi stores;
    private final EntitlementApi benefits;
    private final OrderApi orders;
    private final AftersaleApi aftersales;
    private final RuleDecisionPort rules;
    private final Clock clock;
    private final TransactionTemplate tx;
    private String cursor="";
    public JourneyService(JourneyMapper mapper,Commands commands,MemberApi members,StoreApi stores,EntitlementApi benefits,OrderApi orders,AftersaleApi aftersales,RuleDecisionPort rules,Clock clock,PlatformTransactionManager manager,com.lrj.commerce.member.api.MemberGrowthApi memberGrowth,com.lrj.commerce.campaign.api.MarketingAssets assets) {
        this.assets=assets;
        this.memberGrowth=memberGrowth;
        this.mapper=mapper;this.commands=commands;this.members=members;this.stores=stores;this.benefits=benefits;this.orders=orders;this.aftersales=aftersales;this.rules=rules;this.clock=clock;
        tx=new TransactionTemplate(manager);tx.setTimeout(10);
    }
    /** 图必须有界、无环、可达且所有分支收敛到终止节点。 */
    public View create(Actor actor,String key,Definition input) {
        actor.requireAdmin();validate(input);
        return commands.run(actor,"journey.create",key,input,View.class,()->{
            stores.requireActive(actor,input.storeId());
            for(var node:input.nodes())if(node.kind()==Kind.GRANT)benefits.validateBinding(actor.tenantId(),input.storeId(),node.benefit(),input.validFrom(),input.validTo().plusSeconds(input.maxDurationSeconds()));
            mapper.definition(actor.tenantId(),input,JsonCodec.write(input));return view(mapper.find(actor.tenantId(),input.journeyId(),input.version()));
        });
    }
    private void validate(Definition d) {
        Inputs.require(d!=null&&d.version()>0&&d.trigger()!=null&&d.validFrom()!=null&&d.validTo()!=null&&d.validTo().isAfter(d.validFrom()),"旅程版本或生效窗口无效");
        Identifiers.require(d.journeyId());Identifiers.require(d.storeId());Inputs.text(d.name(),128);
        Inputs.require(d.maxDurationSeconds()>=1&&d.maxDurationSeconds()<=2592000&&d.nodes()!=null&&!d.nodes().isEmpty()&&d.nodes().size()<=32,"旅程节点或执行期限超限");
        Inputs.require(Set.of(Trigger.MANUAL,Trigger.ORDER_PAID).contains(d.trigger())||d.controls()!=null,"会员事件旅程必须配置频控");
        if(d.controls()!=null){var c=d.controls();Inputs.require(c.maxEntries()>=1&&c.maxEntries()<=100&&c.notificationLimit()>=1&&c.notificationLimit()<=100&&c.entryWindowSeconds()>=60&&c.entryWindowSeconds()<=2592000&&c.notificationWindowSeconds()>=60&&c.notificationWindowSeconds()<=2592000,"旅程频控参数无效");
            if(c.entryRule()!=null)c.entryRule().requireTrustedFields();
            if(d.trigger()==Trigger.SEGMENT_ENTERED)Identifiers.require(c.segmentId());else Inputs.require(c.segmentId()==null,"只有人群触发可绑定segmentId");
        }
        var graph=new HashMap<String,Node>();
        for(var n:d.nodes()) {
            Inputs.require(n!=null&&n.kind()!=null,"节点类型缺失");Identifiers.require(n.id());Inputs.require(graph.put(n.id(),n)==null,"节点标识重复");
            Inputs.require(n.kind()==Kind.WAIT?n.seconds()!=null&&n.seconds()>=1&&n.seconds()<=604800:n.seconds()==null,"等待参数无效");
            Inputs.require(n.kind()==Kind.DECIDE?n.rule()!=null&&n.yesNext()!=null&&n.noNext()!=null:n.rule()==null&&n.yesNext()==null&&n.noNext()==null,"分支参数无效");
            Inputs.require(n.kind()==Kind.GRANT?n.benefit()!=null:n.benefit()==null,"权益参数无效");
            if(n.kind()==Kind.NOTIFY){Inputs.text(n.title(),128);Inputs.text(n.body(),1000);}else Inputs.require(n.title()==null&&n.body()==null,"非触达节点不能携带内容");
            if(n.kind()==Kind.DECIDE)n.rule().requireTrustedFields();
            Inputs.require(n.kind()==Kind.END||n.kind()==Kind.DECIDE?n.next()==null:n.next()!=null,"后继节点无效");
        }
        var visiting=new HashSet<String>();var visited=new HashSet<String>();walk(d.entry(),graph,visiting,visited);
        Inputs.require(visited.size()==graph.size(),"存在不可达节点");
    }
    private void walk(String id,Map<String,Node> graph,Set<String> visiting,Set<String> visited) {
        Inputs.require(id!=null&&graph.containsKey(id),"引用节点不存在");if(visited.contains(id))return;
        Inputs.require(visiting.add(id),"旅程不能存在循环");var n=graph.get(id);
        if(n.kind()==Kind.DECIDE){walk(n.yesNext(),graph,visiting,visited);walk(n.noNext(),graph,visiting,visited);}else if(n.kind()!=Kind.END)walk(n.next(),graph,visiting,visited);
        visiting.remove(id);visited.add(id);
    }
    /** 查询每个旅程的最新内容版本，实例仍保留自己的旧版本。 */
    public List<View> definitions(Actor actor,String after,int limit){actor.requireAdmin();Inputs.page(after,limit);return mapper.definitions(actor.tenantId(),after,limit).stream().map(this::view).toList();}
    /** 审批状态和乐观版本共同约束发布，已暂停版本可重新启用。 */
    public View change(Actor actor,String key,String id,long version,long expected,String action) {
        actor.requireAdmin();Identifiers.require(id);Inputs.require(version>0&&expected>=0&&Set.of("submit","approve","reject","publish","pause").contains(action),"旅程审批参数无效");
        return commands.run(actor,"journey."+action,key,List.of(id,version,expected),View.class,()->{
            var row=mapper.group(actor.tenantId(),id).stream().filter(r->r.version()==version).findFirst().orElseThrow(()->new DomainException(DomainException.Code.NOT_FOUND,"旅程版本不存在"));
            boolean allowed=switch(action){case "submit"->row.status().equals("DRAFT");case "approve","reject"->row.status().equals("IN_REVIEW");case "publish"->Set.of("APPROVED","PAUSED").contains(row.status());case "pause"->row.status().equals("PUBLISHED");default->false;};
            if(!allowed||row.lockVersion()!=expected)throw conflict("旅程状态或版本冲突");
            String next=switch(action){case "submit"->"IN_REVIEW";case "approve"->"APPROVED";case "reject"->"REJECTED";case "publish"->"PUBLISHED";default->"PAUSED";};
            if(action.equals("publish")){var d=view(row).content();if(!clock.instant().isBefore(d.validTo()))throw conflict("旅程入组窗口已结束");stores.requireActive(actor,d.storeId());mapper.pauseOthers(actor.tenantId(),id);}
            if(mapper.change(actor.tenantId(),id,version,expected,next)!=1)throw conflict("旅程并发修改");return view(mapper.find(actor.tenantId(),id,version));
        });
    }
    /** 相同触发键只产生一个实例，换会员重用该键属于冲突。 */
    public Instance enroll(Actor actor,String key,Start input) {
        actor.requireAdmin();Inputs.require(input!=null&&input.version()>0,"入组参数无效");Identifiers.require(input.journeyId());Identifiers.require(input.memberId());Identifiers.require(input.eventKey());
        return commands.run(actor,"journey.enroll",key,input,Instance.class,()->{
            var row=Inputs.found(mapper.find(actor.tenantId(),input.journeyId(),input.version()));var d=view(row).content();
            if(!row.status().equals("PUBLISHED")||d.trigger()!=Trigger.MANUAL)throw conflict("旅程不允许手工入组");
            requireWindow(d);members.requireActive(actor,input.memberId());return start(actor.tenantId(),d,input.memberId(),null,input.eventKey());
        });
    }
    private void requireWindow(Definition d){var now=clock.instant();if(now.isBefore(d.validFrom())||!now.isBefore(d.validTo()))throw conflict("旅程不在入组窗口");}
    private Instance start(String tenant,Definition d,String member,String order,String event) {return start(tenant,d,member,order,event,false);}
    private Instance start(String tenant,Definition d,String member,String order,String event,boolean automatic) {
        var existing=mapper.byEvent(tenant,d.journeyId(),d.version(),event);
        if(existing!=null){if(!existing.memberId().equals(member)||!Objects.equals(existing.orderId(),order))throw conflict("触发键已经绑定其他来源");return existing;}
        String effect=JsonCodec.hash("entry/"+d.journeyId()+"/"+d.version()+"/"+event);
        if(d.controls()!=null){
            mapper.ensureCap(tenant,d.journeyId(),member);var cap=mapper.lockCap(tenant,d.journeyId(),member);
            if(mapper.effectExists(tenant,effect))return null;
            long bucket=window(d.controls().entryWindowSeconds());int count=cap.entryWindow()==bucket?cap.entries():0;
            if(count>=d.controls().maxEntries()){
                if(!automatic)throw conflict("会员已达到当前旅程窗口入组上限");
                mapper.cap(tenant,d.journeyId(),member,false,bucket,count,true);mapper.effect(tenant,effect,d,member,"ENTRY_SUPPRESSED");return null;
            }
            mapper.cap(tenant,d.journeyId(),member,false,bucket,count+1,false);
        }
        var now=clock.instant();mapper.instance(tenant,UUID.randomUUID().toString(),d,member,order,event,now,now.plusSeconds(d.maxDurationSeconds()));
        mapper.effect(tenant,effect,d,member,"ENROLLED");return mapper.byEvent(tenant,d.journeyId(),d.version(),event);
    }
    private long window(int seconds){long at=clock.instant().getEpochSecond();return at/seconds*seconds;}
    /** 按认证身份限制实例列表，不开放任意memberId过滤。 */
    public List<Instance> instances(Actor actor,String after,int limit){Inputs.page(after,limit);String member=actor.role()==Actor.Role.ADMIN?null:members.current(actor).memberId();return mapper.instances(actor.tenantId(),member,after,limit);}
    /** 取消不逆转已执行效果；隔离重试必须仍在截止时间内。 */
    public Instance control(Actor actor,String key,String id,String action) {
        actor.requireAdmin();Identifiers.require(id);Inputs.require(Set.of("cancel","retry").contains(action),"实例动作无效");
        return commands.run(actor,"journey."+action,key,id,Instance.class,()->{
            var old=Inputs.found(mapper.lock(actor.tenantId(),id));
            if(action.equals("retry")){if(old.status()!=State.ISOLATED||!clock.instant().isBefore(old.deadline()))throw conflict("实例不允许重试");}
            else if(!active(old))throw conflict("实例已终止");
            if(mapper.control(actor.tenantId(),id,old.version(),action.equals("retry")?"RUNNING":"CANCELLED",clock.instant())!=1)throw conflict("实例并发修改");return mapper.findInstance(actor.tenantId(),id);
        });
    }
    /** 站内信是持久化的业务触达，不调用外部消息系统。 */
    public List<Notification> notifications(Actor actor,String after,int limit){Inputs.page(after,limit);return mapper.notifications(actor.tenantId(),members.current(actor).memberId(),after,limit);}
    public String consumer(){return "journey-order-paid-v1";}
    public Set<String> types(){return Set.of("order.paid.v1","member.registered.v1","member.level.changed.v1","segment.member.entered.v1");}
    /** 事件触发只读当前可信事实，发布之前的事件不追溯执行。 */
    public void handle(Event event) {
        String member,orderId=null,store=null,segment=null;Trigger trigger;
        switch(event.eventType()){
            case "order.paid.v1" -> {var order=orders.internalRead(event.tenantId(),event.aggregateId());if(aftersales.fullyReturned(event.tenantId(),order.orderId()))return;if(!Set.of("PAID","FULFILLING","COMPLETED").contains(order.status()))throw conflict("支付触发订单状态无效");member=order.memberId();orderId=order.orderId();store=order.storeId();trigger=Trigger.ORDER_PAID;}
            case "member.registered.v1" -> {member=JsonCodec.read(event.payloadJson(),com.lrj.commerce.member.api.MemberGrowthApi.Registered.class).memberId();trigger=Trigger.MEMBER_REGISTERED;}
            case "member.level.changed.v1" -> {member=JsonCodec.read(event.payloadJson(),com.lrj.commerce.member.api.MemberGrowthApi.LevelChanged.class).memberId();trigger=Trigger.LEVEL_CHANGED;}
            case "segment.member.entered.v1" -> {var entry=JsonCodec.read(event.payloadJson(),com.lrj.commerce.campaign.api.SegmentApi.Entered.class);member=entry.memberId();segment=entry.segmentId();trigger=Trigger.SEGMENT_ENTERED;
                var source=assets.sources(event.tenantId(),member,List.of(new com.lrj.commerce.campaign.api.MarketingAssets.Ref(entry.audienceId(),entry.snapshotVersion())),clock.instant()).getFirst();if(!source.match().equals("HIT"))return;}
            default -> throw conflict("未知旅程事件");
        }
        var facts=memberGrowth.facts(event.tenantId(),member);if(!facts.status().equals("ACTIVE"))return;
        var rows=mapper.triggered(event.tenantId(),trigger.name(),store,clock.instant(),event.createdAt());Inputs.require(rows.size()<=10,"匹配的自动旅程超过10个");
        for(var row:rows){var d=view(row).content();if(trigger==Trigger.SEGMENT_ENTERED&&!Objects.equals(segment,d.controls().segmentId()))continue;
            if(d.controls()!=null&&d.controls().entryRule()!=null&&rules.evaluate(d.controls().entryRule().toCondition(),com.lrj.commerce.campaign.api.MemberRuleFacts.from(facts,orderId==null?null:orders.internalRead(event.tenantId(),orderId).payable()))!=Condition.Truth.MATCH)continue;
            start(event.tenantId(),d,member,orderId,trigger==Trigger.ORDER_PAID?orderId:event.eventId(),true);
        }
    }
    /** 全额退款消费者先取得实例锁，与执行节点的实例到权益锁顺序一致。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void cancelForOrder(String tenant,String order) {
        var rows=mapper.orderInstances(tenant,order);Inputs.require(rows.size()<=10,"订单旅程数量异常");
        for(var row:rows)if(active(row)&&mapper.control(tenant,row.instanceId(),row.version(),"CANCELLED",clock.instant())!=1)throw conflict("旅程取消并发冲突");
    }
    private boolean active(Instance i){return Set.of(State.RUNNING,State.WAITING,State.ISOLATED).contains(i.status());}
    /** 每轮最多4租户、每租户5实例；下轮从游标后继续，避免大租户独占。 */
    public synchronized int tick(){var tenants=mapper.tenants(cursor,clock.instant());if(tenants.isEmpty()){cursor="";return 0;}int count=0;for(var tenant:tenants)count+=pumpTenant(tenant);cursor=tenants.getLast();return count;}
    /** 管理台只能触发当前租户的有限批次。 */
    public int pump(Actor actor){actor.requireAdmin();return pumpTenant(actor.tenantId());}
    private int pumpTenant(String tenant) {
        int count=0;
        for(var candidate:mapper.due(tenant,clock.instant())) {
            // 领取前的候选可能已被另一执行器推进，失败计数必须绑定真正执行的节点版本。
            var attempted=new java.util.concurrent.atomic.AtomicReference<>(candidate);
            try {if(Boolean.TRUE.equals(tx.execute(s->{var row=mapper.dueLock(tenant,candidate.instanceId(),clock.instant());if(row==null)return false;attempted.set(row);execute(tenant,row);return true;})))count++;}
            catch(RuntimeException failure){
                // 节点事务已回滚，独立事务仅记录有界重试，不保留可能包含敏感数据的异常文本。
                var retryAt=clock.instant().plusSeconds((1<<Math.min(attempted.get().attempts()+1,5))+java.util.concurrent.ThreadLocalRandom.current().nextInt(2));
                tx.executeWithoutResult(s->mapper.failed(tenant,candidate.instanceId(),attempted.get().version(),retryAt));
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("journey retry id={} errorType={}",candidate.instanceId(),failure.getClass().getSimpleName());
            }
        }return count;
    }
    private void execute(String tenant,Instance row) {
        var now=clock.instant();
        if(!now.isBefore(row.deadline())){advance(tenant,row,row.currentNode(),State.TIMED_OUT,now,"DEADLINE");return;}
        if(row.orderId()!=null&&aftersales.fullyReturned(tenant,row.orderId())){advance(tenant,row,row.currentNode(),State.CANCELLED,now,"ORDER_REFUNDED");return;}
        var d=view(Inputs.found(mapper.find(tenant,row.journeyId(),row.journeyVersion()))).content();
        var node=d.nodes().stream().filter(n->n.id().equals(row.currentNode())).findFirst().orElseThrow();
        Inputs.require(row.steps()<32,"旅程步数超过上限");var member=memberGrowth.facts(tenant,row.memberId());
        if(!member.status().equals("ACTIVE")){advance(tenant,row,row.currentNode(),State.CANCELLED,now,"MEMBER_INACTIVE");return;}
        String next=node.next();State state=State.RUNNING;Instant due=now;String result=null;
        switch(node.kind()) {
            case END -> {next=node.id();state=State.COMPLETED;result="FINISHED";mapper.effect(tenant,JsonCodec.hash("complete/"+row.instanceId()),d,row.memberId(),"COMPLETED");}
            case WAIT -> {state=State.WAITING;due=now.plusSeconds(node.seconds());}
            case DECIDE -> {
                Map<String,Fact> facts=com.lrj.commerce.campaign.api.MemberRuleFacts.from(memberGrowth.facts(tenant,member.memberId()),row.orderId()==null?null:orders.internalRead(tenant,row.orderId()).payable());
                var truth=rules.evaluate(node.rule().toCondition(),facts);
                if(truth==Condition.Truth.UNKNOWN){next=node.id();state=State.COMPLETED;result="RULE_UNKNOWN";}else next=truth==Condition.Truth.MATCH?node.yesNext():node.noNext();
            }
            case GRANT -> benefits.grantFromJourney(tenant,row.memberId(),d.storeId(),JsonCodec.hash(row.instanceId()+"/"+node.id()),row.orderId(),node.benefit());
            case NOTIFY -> {
                boolean allowed=true;
                if(d.controls()!=null){mapper.ensureCap(tenant,d.journeyId(),row.memberId());var cap=mapper.lockCap(tenant,d.journeyId(),row.memberId());long bucket=window(d.controls().notificationWindowSeconds());int count=cap.notificationWindow()==bucket?cap.notifications():0;allowed=count<d.controls().notificationLimit();mapper.cap(tenant,d.journeyId(),row.memberId(),true,bucket,allowed?count+1:count,!allowed);}
                if(allowed)mapper.notify(tenant,UUID.randomUUID().toString(),row.instanceId(),node.id(),row.memberId(),node.title(),node.body());
                mapper.effect(tenant,JsonCodec.hash("notify/"+row.instanceId()+"/"+node.id()),d,row.memberId(),allowed?"NOTIFIED":"NOTIFY_SUPPRESSED");
            }
        }
        advance(tenant,row,next,state,due,result);
    }
    /** 执行计数与商业收入分析分开，避免将触达次数伪装为营销增量效果。 */
    public List<EffectSummary> effects(Actor actor,String store,Instant from,Instant to,String after,int limit){actor.requireAdmin();stores.requireActive(actor,store);Inputs.page(after,limit);Inputs.require(from!=null&&to!=null&&to.isAfter(from)&&Duration.between(from,to).compareTo(Duration.ofDays(93))<=0,"分析窗口需为93天内");return mapper.effects(actor.tenantId(),store,from,to,after,limit);}
    private void advance(String tenant,Instance old,String node,State status,Instant due,String result){if(mapper.advance(tenant,old,node,status.name(),due,result)!=1)throw conflict("旅程检查点并发冲突");}
    private View view(JourneyMapper.Row row){return new View(JsonCodec.read(row.definitionJson(),Definition.class),row.status(),row.lockVersion());}
    private DomainException conflict(String message){return new DomainException(DomainException.Code.CONFLICT,message);}
}
