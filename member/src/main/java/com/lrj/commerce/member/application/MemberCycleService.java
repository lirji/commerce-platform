package com.lrj.commerce.member.application;

import com.lrj.commerce.member.api.*;
import com.lrj.commerce.member.infrastructure.*;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 会员行锁串行化来源和考核；到期与退款重放不会重复产生同一考核事实。 */
@Service
public class MemberCycleService implements MemberCycleApi {
    private final CycleMapper mapper;
    private final GrowthMapper growth;
    private final MemberMapper members;
    private final Commands commands;
    private final Outbox outbox;
    private final Clock clock;
    private final TransactionTemplate tx;

    public MemberCycleService(CycleMapper mapper, GrowthMapper growth, MemberMapper members,
                              Commands commands, Outbox outbox, Clock clock, PlatformTransactionManager manager) {
        this.mapper=mapper;this.growth=growth;this.members=members;this.commands=commands;
        this.outbox=outbox;this.clock=clock;this.tx=new TransactionTemplate(manager);this.tx.setTimeout(10);
    }

    /** 时间和版本不可修改，策略更换显式重新锚定周期，不重写历史快照。 */
    public Policy publish(Actor actor,String key,Policy input) {
        actor.requireAdmin();
        Inputs.require(input!=null && input.version()>0 && input.effectiveFrom()!=null,"周期策略版本或生效时间无效");
        Inputs.require(input.periodDays()>=1 && input.periodDays()<=366,"周期需为1至366日");
        Inputs.require(input.levels()!=null && !input.levels().isEmpty() && input.levels().size()<=8,"等级需为1至8档");
        long previous=-1;var codes=new HashSet<String>();
        for(var level:input.levels()) {
            Inputs.require(level!=null,"等级不能为空");Identifiers.require(level.code());
            Inputs.require(codes.add(level.code()) && level.minimumGrowth()>previous && level.minimumGrowth()<=1_000_000_000_000L,"等级代码需唯一，门槛递增且不超限");
            previous=level.minimumGrowth();
        }
        Inputs.require(input.levels().getFirst().minimumGrowth()==0,"首档门槛必须为0");
        var policy=new Policy(input.version(),input.effectiveFrom().truncatedTo(ChronoUnit.MILLIS),input.periodDays(),List.copyOf(input.levels()));
        return commands.run(actor,"member.cycle.policy",key,policy,Policy.class,()->{
            Inputs.require(!policy.effectiveFrom().isBefore(clock.instant().minusSeconds(60)) && !policy.effectiveFrom().isAfter(clock.instant().plusSeconds(31_536_000)),"生效时间应为现在或未来一年内");
            mapper.policy(actor.tenantId(),policy,JsonCodec.write(policy));return policy;
        });
    }

    /** 历史策略按稳定版本游标查询。 */
    public List<Policy> policies(Actor actor,long after,int limit) {
        actor.requireAdmin();Inputs.require(after>=0,"游标无效");Inputs.page("",limit);
        return mapper.policies(actor.tenantId(),after,limit).stream().map(this::policy).toList();
    }

    /** 运营手动考核与定时考核走相同规则。 */
    public View evaluate(Actor actor,String key,String id) {
        actor.requireAdmin();Identifiers.require(id);
        return commands.run(actor,"member.cycle.evaluate",key,id,View.class,()->{assessLocked(actor.tenantId(),id);return view(actor.tenantId(),id);});
    }

    /** 查询不偷偷推进等级；最后考核周期用于展示陈旧状态。 */
    public View read(Actor actor,String id) {
        Identifiers.require(id);
        if(actor.role()!=Actor.Role.ADMIN && (actor.role()!=Actor.Role.MEMBER || !Inputs.found(members.byActor(actor.tenantId(),actor.actorId())).memberId().equals(id)))
            throw new DomainException(DomainException.Code.FORBIDDEN,"不能读取其他会员周期");
        return view(actor.tenantId(),id);
    }

    /** 本人身份取自数据库绑定。 */
    public View current(Actor actor) {
        if(actor.role()!=Actor.Role.MEMBER)throw new DomainException(DomainException.Code.FORBIDDEN,"仅会员可读取本人周期");
        return view(actor.tenantId(),Inputs.found(members.byActor(actor.tenantId(),actor.actorId())).memberId());
    }

    /** 原始业务时间保持不变，退款只能修正原来源净额。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void contribute(String tenant,String member,String source,Instant occurredAt,long contribution) {
        Inputs.found(growth.lockMember(tenant,member));
        Instant at=Objects.requireNonNull(occurredAt).truncatedTo(ChronoUnit.MILLIS);
        var existing=mapper.contribution(tenant,source);
        if(existing==null)mapper.insertContribution(tenant,member,source,at,contribution);
        else {
            if(!existing.memberId().equals(member)||!existing.occurredAt().equals(at))throw new DomainException(DomainException.Code.CONFLICT,"周期来源事实不一致");
            if(mapper.updateContribution(tenant,source,contribution)!=1)throw new DomainException(DomainException.Code.CONFLICT,"周期来源更新冲突");
        }
    }

    /** 成长事务调用时共用行锁与提交边界，不形成两个等级写入权威。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean assess(String tenant,String member) { return assessLocked(tenant,member); }

    /** 每轮最多20名，成功推进边界即移出到期集合，多实例在会员锁后复核。 */
    public void tick() {
        for(var due:mapper.due(clock.instant(),20))
            tx.executeWithoutResult(status->assessLocked(due.tenantId(),due.memberId()));
    }

    private boolean assessLocked(String tenant,String id) {
        var member=Inputs.found(growth.lockMember(tenant,id));
        var policy=policy(mapper.effective(tenant,clock.instant()));
        if(policy==null)return false;
        // 注销是终态，不因后台考核再次写资料或触发新的权益。
        if(member.status().equals("CLOSED"))return true;
        long periodSeconds=Math.multiplyExact(policy.periodDays(),86_400L);
        long index=Duration.between(policy.effectiveFrom(),clock.instant()).getSeconds()/periodSeconds;
        Instant start=policy.effectiveFrom().plusSeconds(index*periodSeconds),end=start.plusSeconds(periodSeconds);
        long current=mapper.sum(tenant,id,start,end);
        long retention=index==0?0:mapper.sum(tenant,id,start.minusSeconds(periodSeconds),start);
        long qualifying=Math.max(0,Math.max(current,retention));
        String level=policy.levels().getFirst().code();
        for(var threshold:policy.levels())if(qualifying>=threshold.minimumGrowth())level=threshold.code();
        var old=mapper.view(tenant,id);
        boolean newAssessment=old==null || old.policyVersion()!=policy.version() || !old.cycleStart().equals(start) || !old.memberLevel().equals(level);
        boolean changed=newAssessment || old.currentGrowth()!=current || old.retentionGrowth()!=retention;
        long version=old==null?1:old.version()+1;
        if(changed) {
            var next=new View(id,true,policy.version(),start,end,current,retention,level,version);
            if(old==null)mapper.insertView(tenant,next);
            else if(mapper.updateView(tenant,next,old.version())!=1)throw new DomainException(DomainException.Code.CONFLICT,"周期考核版本冲突");
        }
        if(!member.memberLevel().equals(level)) {
            growth.level(tenant,id,level);
            outbox.append(tenant,"member.level.changed.v1",id,member.version()+1,new MemberGrowthApi.LevelChanged(id,member.memberLevel(),level,current,policy.version()));
        }
        if(newAssessment)outbox.append(tenant,"member.cycle.assessed.v1",id,version,new Assessed(id,policy.version(),start,end,level,current,retention));
        return true;
    }

    private View view(String tenant,String id) {
        var member=Inputs.found(members.find(tenant,id));var snapshot=mapper.view(tenant,id);
        return snapshot==null?new View(id,false,0,null,null,0,0,member.memberLevel(),0):snapshot;
    }
    private Policy policy(CycleMapper.PolicyRow row) {return row==null?null:JsonCodec.read(row.policyJson(),Policy.class);}
}
