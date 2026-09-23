package com.lrj.commerce.member.application;

import com.lrj.commerce.member.api.*;
import com.lrj.commerce.member.infrastructure.*;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.Actor;
import com.lrj.commerce.runtime.api.Inputs;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.*;
import java.time.Clock;
import java.util.*;

/** 积分冻结与原批次分配持久化，支付未知不会提前恢复可用额。 */
@Service
public class PointsSpendService implements PointsSpendApi {
    private static final int ALLOCATION_LIMIT=200;
    private enum State {
        RESERVED("RESERVED"), CONSUMED("CONSUMED"), RELEASED("RELEASED");
        private final String code;
        State(String code){this.code=code;}
    }
    private final PointsMapper lots;
    private final PointsSpendMapper holds;
    private final MemberMapper members;
    private final MemberPointsService ledger;
    private final Clock clock;
    public PointsSpendService(PointsMapper lots,PointsSpendMapper holds,MemberMapper members,MemberPointsService ledger,Clock clock) {
        this.lots=lots;this.holds=holds;this.members=members;this.ledger=ledger;this.clock=clock;
    }

    /** 只使用可分配的前200批次，不能用报价承诺无法在有界事务中冻结的碎片余额。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public Application preview(Actor actor,String member,long requested,String remainingAmount) {
        Inputs.require(requested>=0 && requested<=1_000_000_000L,"请求消费积分需为0至10亿整数");
        if(requested==0)return null;
        ledger.lock(actor.tenantId(),member);requireOwner(actor,member);
        var row=lots.effective(actor.tenantId(),clock.instant());
        if(row==null)throw conflict("尚未配置积分消费规则");
        var policy=JsonCodec.read(row.policyJson(),MemberPointsApi.Policy.class);
        if(!policy.spendEnabled())throw conflict("积分消费当前未开启");
        long available=ledger.wallet(actor.tenantId(),member).available();
        long assignable=lots.availableLots(actor.tenantId(),member,clock.instant(),ALLOCATION_LIMIT).stream().mapToLong(PointsMapper.Lot::remaining).sum();
        long selected=Math.min(requested,Math.min(available,assignable));
        long base=new Money(new BigDecimal(remainingAmount)).minorUnits();
        long cap=BigInteger.valueOf(base).multiply(BigInteger.valueOf(policy.maxDeductionBps())).divide(BigInteger.valueOf(10000)).longValueExact();
        long value=BigInteger.valueOf(selected).multiply(BigInteger.valueOf(100)).divide(BigInteger.valueOf(policy.pointsPerYuan())).longValueExact();
        long discount=Math.min(cap,value);
        if(discount==0)return null;
        long used=BigInteger.valueOf(discount).multiply(BigInteger.valueOf(policy.pointsPerYuan())).add(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100)).longValueExact();
        return new Application(policy.version(),used,Money.minor(discount).amount().toPlainString());
    }

    /** 报价与实际预留必须一致，额度被其他订单占用后返回冲突而非静默少抵扣。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void reserve(Actor actor,String order,String member,Application application,String remainingAmount) {
        if(application==null)return;
        Identifiers.require(order);ledger.lock(actor.tenantId(),member);requireOwner(actor,member);
        var existing=holds.hold(actor.tenantId(),order);
        if(existing!=null) {
            if(!existing.memberId().equals(member) || existing.points()!=application.points() || existing.policyVersion()!=application.policyVersion() || new BigDecimal(existing.discount()).compareTo(new BigDecimal(application.discount()))!=0 || !existing.status().equals(State.RESERVED.code))throw conflict("订单积分持有事实冲突");
            return;
        }
        var actual=preview(actor,member,application.points(),remainingAmount);
        if(!Objects.equals(actual,application))throw conflict("积分余额、有效期或规则已变化，请重新报价");
        holds.insert(actor.tenantId(),order,member,application);
        long remaining=application.points();int sequence=0;
        for(var lot:lots.availableLots(actor.tenantId(),member,clock.instant(),ALLOCATION_LIMIT)) {
            if(remaining==0)break;long amount=Math.min(remaining,lot.remaining());
            ledger.changeLot(actor.tenantId(),lot,lot.remaining()-amount,Math.addExact(lot.held(),amount),lot.expired());
            holds.allocate(actor.tenantId(),order,lot.lotId(),amount,sequence++);remaining-=amount;
        }
        if(remaining!=0)throw conflict("积分批次在预留时已变化");
        var account=lots.account(actor.tenantId(),member);ledger.changeAccount(actor.tenantId(),member,account,account.debt());
        ledger.entry(actor.tenantId(),member,MemberPointsApi.Action.HOLD,order,0,application.policyVersion(),"下单冻结"+application.points()+"积分，等待支付终态");
    }

    /** 与订单支付确认同事务，重复确认不重复扣分。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void confirm(String tenant,String order,String member) {
        ledger.lock(tenant,member);var hold=holds.hold(tenant,order);if(hold==null)return;owned(hold,member);
        if(hold.status().equals(State.CONSUMED.code))return;
        if(!hold.status().equals(State.RESERVED.code))throw conflict("已释放积分不可再核销");
        for(var allocation:holds.allocations(tenant,order)) {
            var lot=Inputs.found(lots.lot(tenant,allocation.lotId()));
            Inputs.require(lot.held()>=allocation.points(),"冻结积分不足");
            ledger.changeLot(tenant,lot,lot.remaining(),lot.held()-allocation.points(),lot.expired());
        }
        transition(tenant,order,State.RESERVED,State.CONSUMED);
        var account=lots.account(tenant,member);ledger.changeAccount(tenant,member,account,account.debt());
        ledger.entry(tenant,member,MemberPointsApi.Action.SPEND,order,-hold.points(),hold.policyVersion(),"支付确认核销抵扣积分");
    }

    /** 原有效期不会因取消刷新；返还先抵扣欠项，避免退奖励后取消产生虚假负担。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void release(String tenant,String order,String member) {
        ledger.lock(tenant,member);var hold=holds.hold(tenant,order);if(hold==null)return;owned(hold,member);
        if(hold.status().equals(State.RELEASED.code))return;
        if(!hold.status().equals(State.RESERVED.code))throw conflict("已核销积分需经售后返还");
        var account=lots.account(tenant,member);long debt=account.debt(),forfeited=0;
        for(var allocation:holds.allocations(tenant,order)) {
            var restored=restore(tenant,allocation.lotId(),allocation.points(),debt,true);debt=restored.debt();forfeited+=restored.forfeited();
        }
        transition(tenant,order,State.RESERVED,State.RELEASED);ledger.changeAccount(tenant,member,account,debt);
        ledger.entry(tenant,member,MemberPointsApi.Action.RELEASE,order,-forfeited,hold.policyVersion(),"取消释放"+hold.points()+"积分；原期限失效"+forfeited);
    }

    /** 返还按原分配顺序且累计受数据库约束，零现金售后也必须调用。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void refund(String tenant,String order,String member,String caseId,long amount) {
        Identifiers.require(caseId);Inputs.require(amount>=0,"返还积分不能为负");
        ledger.lock(tenant,member);var hold=holds.hold(tenant,order);
        if(hold==null){Inputs.require(amount==0,"订单没有积分持有记录");return;}owned(hold,member);
        var previous=holds.refund(tenant,caseId);
        if(previous!=null){if(!previous.orderId().equals(order)||previous.points()!=amount)throw conflict("积分返还来源冲突");return;}
        if(!hold.status().equals(State.CONSUMED.code) || amount>hold.points()-hold.returnedPoints())throw conflict("积分返还超过原核销额度或状态不符");
        var account=lots.account(tenant,member);long remaining=amount,debt=account.debt(),forfeited=0;
        for(var allocation:holds.allocations(tenant,order)) {
            long count=Math.min(remaining,allocation.points()-allocation.returnedPoints());if(count==0)continue;
            var restored=restore(tenant,allocation.lotId(),count,debt,false);debt=restored.debt();forfeited+=restored.forfeited();
            if(holds.allocationReturn(tenant,order,allocation.lotId(),count)!=1)throw conflict("积分批次返还上限冲突");remaining-=count;if(remaining==0)break;
        }
        if(remaining!=0 || holds.returned(tenant,order,amount)!=1)throw conflict("积分返还总额冲突");
        holds.refundInsert(tenant,order,caseId,amount);
        if(amount>0) {
            ledger.changeAccount(tenant,member,account,debt);
            ledger.entry(tenant,member,MemberPointsApi.Action.REFUND,caseId,amount-forfeited,hold.policyVersion(),"售后返还"+amount+"积分；原期限失效"+forfeited);
        }
    }

    private record Restored(long debt,long forfeited) { }
    private Restored restore(String tenant,String lotId,long amount,long debt,boolean releaseHeld) {
        var lot=Inputs.found(lots.lot(tenant,lotId));long held=lot.held();
        if(releaseHeld){Inputs.require(held>=amount,"冻结积分余额不足");held-=amount;}
        long offset=Math.min(debt,amount),returned=amount-offset;
        boolean expired=!clock.instant().isBefore(lot.expiresAt());
        ledger.changeLot(tenant,lot,lot.remaining()+(expired?0:returned),held,lot.expired()+(expired?returned:0));
        return new Restored(debt-offset,expired?returned:0);
    }
    private void requireOwner(Actor actor,String id) {
        var member=Inputs.found(members.byActor(actor.tenantId(),actor.actorId()));
        if(!member.memberId().equals(id) || !member.status().equals("ACTIVE"))throw conflict("会员积分账户不可消费");
    }
    private void owned(PointsSpendMapper.Hold hold,String member){if(!hold.memberId().equals(member))throw conflict("订单积分主体不一致");}
    private void transition(String tenant,String order,State before,State after){if(holds.transition(tenant,order,before.code,after.code)!=1)throw conflict("积分持有状态已变化");}
    private DomainException conflict(String message){return new DomainException(DomainException.Code.CONFLICT,message);}
}
