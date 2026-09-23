package com.lrj.commerce.benefit.application;
import com.lrj.commerce.benefit.api.*;
import com.lrj.commerce.benefit.infrastructure.MemberBenefitMapper;
import com.lrj.commerce.member.api.*;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 周期礼包在一笔本地事务中受理，实际权益到账复用可靠消费者。 */
@Service
public class MemberBenefitService implements MemberBenefitApi,EventHandler {
    private final MemberBenefitMapper mapper;
    private final MemberCycleApi cycles;
    private final MemberApi members;
    private final EntitlementApi entitlements;
    private final Commands commands;
    private final Clock clock;
    public MemberBenefitService(MemberBenefitMapper mapper,MemberCycleApi cycles,MemberApi members,EntitlementApi entitlements,Commands commands,Clock clock) {
        this.mapper=mapper;this.cycles=cycles;this.members=members;this.entitlements=entitlements;this.commands=commands;this.clock=clock;
    }
    /** 绑定前验证等级与权益有效窗口，避免可见配置无法发放。 */
    public Bundle publish(Actor actor,String key,Bundle input) {
        actor.requireAdmin();Inputs.require(input!=null && input.policyVersion()>0 && input.validUntil()!=null,"礼包策略或期限无效");
        Identifiers.require(input.bindingId());Identifiers.require(input.level());Identifiers.require(input.storeId());
        Inputs.require(input.benefits()!=null && !input.benefits().isEmpty() && input.benefits().size()<=8,"礼包需包含1至8项权益");
        var refs=new HashSet<EntitlementApi.Ref>();
        for(var ref:input.benefits()){Inputs.require(ref!=null && ref.version()>0 && refs.add(ref),"权益引用重复或无效");Identifiers.require(ref.benefitId());}
        var bundle=new Bundle(input.bindingId(),input.policyVersion(),input.level(),input.storeId(),input.validUntil().truncatedTo(ChronoUnit.MILLIS),List.copyOf(input.benefits()));
        return commands.run(actor,"member.benefit.bundle",key,bundle,Bundle.class,()->{
            var policy=cycles.policy(actor,bundle.policyVersion());
            Inputs.require(policy.levels().stream().anyMatch(level->level.code().equals(bundle.level())),"周期策略中没有该等级");
            Inputs.require(bundle.validUntil().isAfter(policy.effectiveFrom()) && bundle.validUntil().isAfter(clock.instant()),"礼包发放期限无效");
            for(var ref:bundle.benefits())entitlements.validateBinding(actor.tenantId(),bundle.storeId(),ref,policy.effectiveFrom(),bundle.validUntil());
            mapper.insert(actor.tenantId(),bundle,JsonCodec.write(bundle));return bundle;
        });
    }
    /** 历史礼包保留，不因失效隐藏运营事实。 */
    public List<Bundle> list(Actor actor,long policyVersion) {
        actor.requireAdmin();Inputs.require(policyVersion>0,"策略版本无效");
        return mapper.list(actor.tenantId(),policyVersion).stream().map(value->JsonCodec.read(value,Bundle.class)).toList();
    }
    /** 补发重验权威周期并串行会员锁，不能指定历史周期刷奖励。 */
    public Receipt grant(Actor actor,String key,String memberId) {
        actor.requireAdmin();Identifiers.require(memberId);
        return commands.run(actor,"member.benefit.grant",key,memberId,Receipt.class,()->{
            cycles.assess(actor.tenantId(),memberId);members.requireActive(actor,memberId);
            return award(actor,cycles.read(actor,memberId));
        });
    }
    public String consumer(){return "member-cycle-benefit-v1";}
    public Set<String> types(){return Set.of("member.cycle.assessed.v1");}
    /** 迟到事件以最新周期为准，旧周期权益不能靠重试复活。 */
    public void handle(Event event) {
        var signal=JsonCodec.read(event.payloadJson(),MemberCycleApi.Assessed.class);
        var actor=new Actor(event.tenantId(),"cycle-benefit-worker",Actor.Role.ADMIN);
        cycles.assess(event.tenantId(),signal.memberId());
        var current=cycles.read(actor,signal.memberId());
        if(!current.enabled() || current.policyVersion()!=signal.policyVersion() || !Objects.equals(current.cycleStart(),signal.cycleStart()) || !current.memberLevel().equals(signal.memberLevel()))return;
        // 非活跃会员拒绝授予，失败事件由既有重试/隔离机制保留供运营核查。
        members.requireActive(actor,signal.memberId());
        award(actor,current);
    }
    private Receipt award(Actor actor,MemberCycleApi.View cycle) {
        if(!cycle.enabled())return new Receipt(cycle.memberId(),List.of());
        String raw=mapper.find(actor.tenantId(),cycle.policyVersion(),cycle.memberLevel());
        if(raw==null)return new Receipt(cycle.memberId(),List.of());
        var bundle=JsonCodec.read(raw,Bundle.class);
        if(!clock.instant().isBefore(bundle.validUntil()))return new Receipt(cycle.memberId(),List.of());
        var grants=new ArrayList<EntitlementApi.View>();
        for(var ref:bundle.benefits()) {
            String source=JsonCodec.hash(JsonCodec.write(List.of(cycle.memberId(),cycle.policyVersion(),cycle.cycleStart(),cycle.memberLevel(),ref)));
            grants.add(entitlements.grantFromLevel(actor.tenantId(),cycle.memberId(),bundle.storeId(),source,ref));
        }
        return new Receipt(cycle.memberId(),List.copyOf(grants));
    }
}
