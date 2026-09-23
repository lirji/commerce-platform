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
import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 积分来源净贡献与批次余额分开；过期和退款不能重复扣回同一份奖励。 */
@Service
public class MemberPointsService implements MemberPointsApi {
    private static final long ACCOUNT_LIMIT=9_000_000_000_000_000L;
    private static final long SOURCE_LIMIT=1_000_000_000_000L;
    private static final int MUTATION_BATCH_LIMIT=200;
    private final PointsMapper mapper;
    private final GrowthMapper memberLocks;
    private final MemberMapper members;
    private final Commands commands;
    private final Clock clock;
    private final TransactionTemplate tx;

    public MemberPointsService(PointsMapper mapper,GrowthMapper memberLocks,MemberMapper members,Commands commands,Clock clock,PlatformTransactionManager manager) {
        this.mapper=mapper;this.memberLocks=memberLocks;this.members=members;this.commands=commands;this.clock=clock;
        tx=new TransactionTemplate(manager);tx.setTimeout(10);
    }

    /** 策略发布不赠送积分，未来规则也不会改变历史订单奖励。 */
    public Policy publish(Actor actor,String key,Policy input) {
        actor.requireAdmin();Inputs.require(input!=null && input.version()>0 && input.effectiveFrom()!=null,"积分策略版本或时间无效");
        Inputs.require(input.expiryDays()>=1 && input.expiryDays()<=366,"积分有效天数需为1至366");
        Inputs.require(input.pointsPerYuan()>=1 && input.pointsPerYuan()<=100000 && input.maxDeductionBps()>=0 && input.maxDeductionBps()<=10000,"积分兑换率或抵扣比例无效");
        Inputs.text(input.earnPerYuan(),16);BigDecimal rate;
        try { rate=new BigDecimal(input.earnPerYuan()).setScale(2,RoundingMode.UNNECESSARY); }
        catch(ArithmeticException|NumberFormatException e){throw new DomainException(DomainException.Code.INVALID_INPUT,"积分获取率最多两位小数");}
        Inputs.require(rate.signum()>=0 && rate.compareTo(new BigDecimal("1000"))<=0,"积分获取率超限");
        var normalized=new Policy(input.version(),input.effectiveFrom().truncatedTo(ChronoUnit.MILLIS),rate.toPlainString(),input.expiryDays(),input.spendEnabled(),input.pointsPerYuan(),input.maxDeductionBps());
        return commands.run(actor,"member.points.policy",key,normalized,Policy.class,()->{
            Inputs.require(!normalized.effectiveFrom().isBefore(clock.instant().minusSeconds(60)) && !normalized.effectiveFrom().isAfter(clock.instant().plusSeconds(31_536_000)),"生效时间应为现在或未来一年内");
            mapper.policy(actor.tenantId(),normalized,JsonCodec.write(normalized));return normalized;
        });
    }

    /** 历史版本不可变，游标稳定且有界。 */
    public List<Policy> policies(Actor actor,long after,int limit) {
        actor.requireAdmin();page(after,limit);return mapper.policies(actor.tenantId(),after,limit).stream().map(this::policy).toList();
    }

    /** 只读钱包按实际时间过滤到期批次，后台延迟不会扩大可用额。 */
    public Wallet wallet(Actor actor,String memberId) {authorize(actor,memberId);return wallet(actor.tenantId(),memberId);}

    /** 本人主体来自认证绑定，不接受客户端会员标识。 */
    public Wallet current(Actor actor) {
        if(actor.role()!=Actor.Role.MEMBER)throw new DomainException(DomainException.Code.FORBIDDEN,"仅会员可读取本人积分");
        return wallet(actor.tenantId(),Inputs.found(members.byActor(actor.tenantId(),actor.actorId())).memberId());
    }

    /** 账本永不原地改写，运营校准产生新记录。 */
    public List<Entry> ledger(Actor actor,String memberId,long after,int limit) {
        authorize(actor,memberId);page(after,limit);return mapper.ledger(actor.tenantId(),memberId,after,limit);
    }

    /** 校准在会员锁后复核账户版本；扣回优先消耗可用批次，缺口明确记录。 */
    public Wallet adjust(Actor actor,String key,String memberId,Adjustment input) {
        actor.requireAdmin();Identifiers.require(memberId);
        Inputs.require(input!=null && input.expectedVersion()>=0 && input.delta()!=0 && input.delta()>=-1_000_000_000L && input.delta()<=1_000_000_000L,"积分校准量或版本无效");Inputs.text(input.reason(),256);
        return commands.run(actor,"member.points.adjust",key,new Object[]{memberId,input},Wallet.class,()->{
            var member=lock(actor.tenantId(),memberId);
            if(!member.status().equals("ACTIVE"))throw conflict("非活跃会员不可人工校准积分");
            var account=mapper.account(actor.tenantId(),memberId);
            if(account.version()!=input.expectedVersion())throw conflict("积分账户版本已变化");
            String source=JsonCodec.hash(JsonCodec.write(List.of("adjust",actor.actorId(),key)));
            long policyVersion=0;
            if(input.delta()>0) {
                var selected=policy(mapper.effective(actor.tenantId(),clock.instant()));
                if(selected==null)throw conflict("请先发布积分获取策略");
                policyVersion=selected.version();
                credit(actor.tenantId(),memberId,source,policyVersion,input.delta(),clock.instant().plus(Duration.ofDays(selected.expiryDays())));
            } else debit(actor.tenantId(),memberId,-input.delta());
            entry(actor.tenantId(),memberId,Action.ADJUST,source,input.delta(),policyVersion,input.reason());return wallet(actor.tenantId(),memberId);
        });
    }

    /** 运维可逐批推进过期，单条命令不会扫描全部历史批次。 */
    public Wallet expire(Actor actor,String key,String memberId) {
        actor.requireAdmin();Identifiers.require(memberId);
        return commands.run(actor,"member.points.expire",key,memberId,Wallet.class,()->{
            lock(actor.tenantId(),memberId);
            for(var lot:mapper.expiredLots(actor.tenantId(),memberId,clock.instant(),100))expireLot(actor.tenantId(),lot);
            return wallet(actor.tenantId(),memberId);
        });
    }

    /** 乱序退款先记来源；只有已完成订单的净现金消费产生奖励。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void observe(String tenant,MemberGrowthApi.OrderFact fact) {
        Identifiers.require(tenant);Inputs.require(fact!=null && fact.orderedAt()!=null,"积分订单事实缺失");
        Identifiers.require(fact.orderId());Identifiers.require(fact.memberId());
        var paid=new Money(new BigDecimal(fact.paid())).amount();lock(tenant,fact.memberId());
        var source=mapper.source(tenant,fact.orderId());
        if(source==null) {
            var selected=policy(mapper.effective(tenant,fact.orderedAt()));
            mapper.sourceInsert(tenant,fact,selected==null?0:selected.version(),selected==null?"0.00":selected.earnPerYuan(),selected==null?1:selected.expiryDays());
            source=mapper.source(tenant,fact.orderId());
        }
        if(!source.memberId().equals(fact.memberId()) || new BigDecimal(source.paid()).compareTo(paid)!=0)throw conflict("订单积分来源事实不一致");
        if(fact.refundId()!=null) {
            Identifiers.require(fact.refundId());var amount=new Money(new BigDecimal(fact.refundAmount())).amount();Inputs.require(amount.signum()>0,"积分退款事实金额无效");
            var old=mapper.refund(tenant,fact.refundId());
            if(old==null)mapper.refundInsert(tenant,fact);
            else if(!old.orderId().equals(fact.orderId()) || new BigDecimal(old.amount()).compareTo(amount)!=0)throw conflict("积分退款来源不一致");
        }
        var refunded=new BigDecimal(mapper.refundTotal(tenant,fact.orderId()));Inputs.require(refunded.compareTo(paid)<=0,"退款累计超过原现金实付");
        boolean completed=source.completed() || fact.completed();
        var net=completed?paid.subtract(refunded):BigDecimal.ZERO;
        var value=net.multiply(new BigDecimal(source.earnRate())).setScale(0,RoundingMode.DOWN);
        Inputs.require(value.compareTo(BigDecimal.valueOf(SOURCE_LIMIT))<=0,"单来源积分奖励超限");
        long contribution=value.longValueExact(),delta=contribution-source.contribution();
        String lotId=JsonCodec.hash("order:"+fact.orderId());
        if(delta>0) {
            credit(tenant,fact.memberId(),lotId,source.policyVersion(),delta,clock.instant().plus(Duration.ofDays(source.expiryDays())));
            entry(tenant,fact.memberId(),Action.EARN,fact.orderId(),delta,source.policyVersion(),"完成订单按原规则奖励净现金消费积分");
        } else if(delta<0) {
            var lot=Inputs.found(mapper.lot(tenant,lotId));expireLot(tenant,lot);lot=mapper.lot(tenant,lotId);
            long reverse=-delta,expiredRelief=Math.min(reverse,lot.expired()),rest=reverse-expiredRelief;
            long free=Math.min(rest,lot.remaining()),debt=rest-free;
            changeLot(tenant,lot,lot.remaining()-free,lot.held(),lot.expired()-expiredRelief);
            var account=mapper.account(tenant,fact.memberId());changeAccount(tenant,fact.memberId(),account,Math.addExact(account.debt(),debt));
            entry(tenant,fact.memberId(),Action.REVOKE,fact.refundId()==null?fact.orderId():fact.refundId(),-rest,source.policyVersion(),"成功退款扣回奖励"+(expiredRelief>0?"；已过期免扣"+expiredRelief:""));
        }
        if(mapper.sourceChange(tenant,fact.orderId(),completed,contribution)!=1)throw conflict("积分订单贡献更新冲突");
    }

    /** 单轮20个批次，每个批次独立提交；实例竞争在会员行锁后再次检查。 */
    public void tick() {
        for(var due:mapper.due(clock.instant(),20))tx.executeWithoutResult(status->{
            lock(due.tenantId(),due.memberId());expireLot(due.tenantId(),Inputs.found(mapper.lot(due.tenantId(),due.lotId())));
        });
    }

    private void credit(String tenant,String member,String lotId,long policyVersion,long amount,Instant expiresAt) {
        var account=mapper.account(tenant,member);var totals=mapper.totals(tenant,member,clock.instant());
        Inputs.require(amount>0 && amount<=SOURCE_LIMIT && Math.addExact(totals.credit(),amount)<=ACCOUNT_LIMIT,"积分入账或账户总额超限");
        long offset=Math.min(account.debt(),amount);
        mapper.insertLot(tenant,new PointsMapper.Lot(lotId,member,policyVersion,amount,amount-offset,0,0,expiresAt.truncatedTo(ChronoUnit.MILLIS)));
        changeAccount(tenant,member,account,account.debt()-offset);
    }

    private void debit(String tenant,String member,long amount) {
        var account=mapper.account(tenant,member);long remaining=amount;
        long total=mapper.totals(tenant,member,clock.instant()).credit();
        for(var lot:mapper.availableLots(tenant,member,clock.instant(),MUTATION_BATCH_LIMIT)) {
            long consumed=Math.min(remaining,lot.remaining());
            if(consumed==0)break;
            changeLot(tenant,lot,lot.remaining()-consumed,lot.held(),lot.expired());remaining-=consumed;
        }
        if(remaining>0 && total>amount-remaining)throw conflict("单次最多处理200个积分批次，请分批调整");
        changeAccount(tenant,member,account,Math.addExact(account.debt(),remaining));
    }

    private void expireLot(String tenant,PointsMapper.Lot lot) {
        if(lot.remaining()==0 || clock.instant().isBefore(lot.expiresAt()))return;
        changeLot(tenant,lot,0,lot.held(),Math.addExact(lot.expired(),lot.remaining()));
        var account=mapper.account(tenant,lot.memberId());changeAccount(tenant,lot.memberId(),account,account.debt());
        entry(tenant,lot.memberId(),Action.EXPIRE,lot.lotId(),-lot.remaining(),lot.policyVersion(),"积分原有效期届满，冻结部分保留供订单终态处理");
    }
    private void changeLot(String tenant,PointsMapper.Lot lot,long remaining,long held,long expired) {
        if(mapper.changeLot(tenant,lot.lotId(),remaining,held,expired)!=1)throw conflict("积分批次更新冲突");
    }
    private void changeAccount(String tenant,String member,PointsMapper.Account account,long debt) {
        Inputs.require(debt>=0 && debt<=ACCOUNT_LIMIT,"待偿扣回积分超限");
        if(mapper.accountChange(tenant,member,debt,account.version())!=1)throw conflict("积分账户更新冲突");
    }
    private void entry(String tenant,String member,Action action,String source,long delta,long policy,String reason) {
        mapper.entry(tenant,member,action.getCode(),source,delta,wallet(tenant,member),policy,reason,clock.instant());
    }
    private MemberApi.View lock(String tenant,String member) {
        var value=Inputs.found(memberLocks.lockMember(tenant,member));mapper.ensure(tenant,member);return value;
    }
    private Wallet wallet(String tenant,String member) {
        var account=mapper.account(tenant,member);var totals=mapper.totals(tenant,member,clock.instant());long debt=account==null?0:account.debt();
        return new Wallet(member,Math.max(0,totals.credit()-debt),totals.held(),debt,totals.credit(),account==null?0:account.version());
    }
    private void authorize(Actor actor,String member) {
        Identifiers.require(member);Inputs.found(members.find(actor.tenantId(),member));
        if(actor.role()!=Actor.Role.ADMIN && (actor.role()!=Actor.Role.MEMBER || !Inputs.found(members.byActor(actor.tenantId(),actor.actorId())).memberId().equals(member)))
            throw new DomainException(DomainException.Code.FORBIDDEN,"不能读取其他会员积分");
    }
    private Policy policy(PointsMapper.PolicyRow row){return row==null?null:JsonCodec.read(row.policyJson(),Policy.class);}
    private void page(long after,int limit){Inputs.require(after>=0,"游标无效");Inputs.page("",limit);}
    private DomainException conflict(String message){return new DomainException(DomainException.Code.CONFLICT,message);}
}
