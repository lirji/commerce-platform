package com.lrj.commerce.benefit.application;
import com.lrj.commerce.benefit.api.CouponApi;
import com.lrj.commerce.benefit.infrastructure.CouponMapper;
import com.lrj.commerce.member.api.MemberApi;
import com.lrj.commerce.store.api.StoreApi;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.Clock;
import java.math.BigDecimal;
import java.util.*;
/** 券发行、使用与订单锁协作；失败时随订单事务整体回滚。 */
@Service
public class CouponService implements CouponApi {
    private final CouponMapper mapper;private final MemberApi members;private final StoreApi stores;private final Commands commands;private final Clock clock;
    public CouponService(CouponMapper mapper,MemberApi members,StoreApi stores,Commands commands,Clock clock){this.mapper=mapper;this.members=members;this.stores=stores;this.commands=commands;this.clock=clock;}
    /** 定义版本创建后不可修改，配额在领取事务中扣减。 */
    public DefinitionView create(Actor actor,String key,Definition input){actor.requireAdmin();Inputs.require(input!=null&&input.version()>0&&input.quota()>0&&input.quota()<=1000000&&input.validFrom()!=null&&input.validTo()!=null&&input.validFrom().isBefore(input.validTo()),"券定义无效");Inputs.require(input.platformFundingBps()==null||(input.platformFundingBps()>=0&&input.platformFundingBps()<=10000),"券资方比例无效");Inputs.require(input.validityDays()==null || (input.validityDays()>=0 && input.validityDays()<=366),"相对券有效天数无效");Inputs.require(input.issuanceMode()==null || Set.of("PUBLIC","SOURCE_ONLY").contains(input.issuanceMode()),"券发行方式无效");Identifiers.require(input.definitionId());Inputs.text(input.name(),128);money(input.minimumSpend());Inputs.require(money(input.discountAmount()).compareTo(Money.ZERO)>0,"券金额必须大于零");return commands.run(actor,"coupon.definition",key,input,DefinitionView.class,()->{stores.requireActive(actor,input.storeId());mapper.definition(actor.tenantId(),input);return definition(mapper.definitionFind(actor.tenantId(),input.definitionId(),input.version()));});}
    /** 消费者可见本店定义，但领取仍需服务端资格校验。 */
    public List<DefinitionView> definitions(Actor actor,String store,String after,int limit){stores.requireActive(actor,store);Inputs.page(after,limit);return mapper.definitions(actor.tenantId(),store,after,limit,actor.role()==Actor.Role.ADMIN).stream().map(this::definition).toList();}
    /** 锁定义序列化配额，当前读检查是否已领，重试不能多占额度。 */
    public Coupon claim(Actor actor,String key,String id,long version){Identifiers.require(id);Inputs.require(version>0,"券版本无效");return commands.run(actor,"coupon.claim",key,List.of(id,version),Coupon.class,()->{
        var member=members.current(actor);members.requireActive(actor,member.memberId());var definition=Inputs.found(mapper.definitionLock(actor.tenantId(),id,version));stores.requireActive(actor,definition.storeId());
        if(!definition.issuanceMode().equals("PUBLIC"))throw conflict("此券只能通过受控活动获得");
        var existing=mapper.existing(actor.tenantId(),member.memberId(),id,version);if(existing!=null)return existing;
        if(clock.instant().isBefore(definition.validFrom())||!clock.instant().isBefore(definition.validTo())||mapper.issue(actor.tenantId(),id,version)!=1)throw conflict("券未生效、已过期或配额不足");
        String coupon=UUID.randomUUID().toString();var validity=validity(definition);mapper.coupon(actor.tenantId(),member.memberId(),coupon,definition,validity.from(),validity.to());return mapper.find(actor.tenantId(),member.memberId(),coupon);
    });}
    /** 兑换目录只绑定受控券，且发行窗口必须完整覆盖兑换窗口。 */
    public void validateExchange(String tenant,String store,String id,long version,java.time.Instant from,java.time.Instant to) {
        var d=Inputs.found(mapper.definitionFind(tenant,id,version));
        Inputs.require(d.issuanceMode().equals("SOURCE_ONLY") && d.storeId().equals(store) && !from.isBefore(d.validFrom()) && !to.isAfter(d.validTo()),"券需为同店受控券，且发行窗口覆盖发放期间");
    }
    /** 来源与发行配额同事务，重复来源绝不再发券。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public Coupon grantFromPoints(String tenant,String member,String store,String source,String id,long version) {
        return grantSource(tenant,member,store,source,id,version,"POINTS");
    }
    /** 批次发券保持独立来源，不能与积分兑换来源混用。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public Coupon grantTargeted(String tenant,String member,String store,String source,String id,long version){return grantSource(tenant,member,store,source,id,version,"TARGETED");}
    /** 旅程来源不能冒用公开领取或定向批次标识。 */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Coupon grantFromJourney(String tenant,String member,String store,String source,String id,long version){return grantSource(tenant,member,store,source,id,version,"JOURNEY");}
    private Coupon grantSource(String tenant,String member,String store,String source,String id,long version,String type){
        Identifiers.require(source);var d=Inputs.found(mapper.definitionLock(tenant,id,version));
        var old=mapper.bySource(tenant,source,type);
        if(old!=null){if(!old.memberId().equals(member)||!old.definitionId().equals(id)||old.version()!=version)throw conflict("券兑换来源冲突");return old;}
        if(!d.storeId().equals(store)||!d.issuanceMode().equals("SOURCE_ONLY")||clock.instant().isBefore(d.validFrom())||!clock.instant().isBefore(d.validTo())||mapper.issue(tenant,id,version)!=1)throw conflict("兑换券状态、有效期或额度不足");
        var coupon=UUID.randomUUID().toString();var validity=validity(d);mapper.sourceCoupon(tenant,member,coupon,source,type,d,validity.from(),validity.to());return mapper.find(tenant,member,coupon);
    }
    /** 已占用或核销的券不强行撤回，避免破坏在途交易承诺。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public String revokeTargeted(String tenant,String member,String coupon,String source){
        var current=Inputs.found(mapper.bySource(tenant,source,"TARGETED"));
        if(!current.couponId().equals(coupon)||!current.memberId().equals(member))throw conflict("撤销券来源不一致");
        if(current.status().equals("REVOKED"))return "REVOKED";
        if(!current.status().equals("AVAILABLE") || !clock.instant().isBefore(current.validTo()))return "COUPON_"+(clock.instant().isBefore(current.validTo())?current.status():"EXPIRED");
        if(mapper.revoke(tenant,coupon)!=1)throw conflict("券撤销状态已变化");return "REVOKED";
    }
    private record Validity(java.time.Instant from,java.time.Instant to) { }
    /** 一次读取Clock，起止共享毫秒精度，避免有效期出现请求内漂移。 */
    private Validity validity(CouponMapper.DefinitionRow d){
        var at=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        return d.validityDays()!=null&&d.validityDays()>0?new Validity(at,at.plus(java.time.Duration.ofDays(d.validityDays()))):new Validity(d.validFrom(),d.validTo());
    }
    public List<Coupon> wallet(Actor actor,String after,int limit){Inputs.page(after,limit);return mapper.wallet(actor.tenantId(),members.current(actor).memberId(),after,limit);}
    /** 报价只校验资格，不消费券，最终占用发生在下单事务。 */
    public Coupon eligible(Actor actor,String id,String store,String gross){Identifiers.require(id);var coupon=Inputs.found(mapper.find(actor.tenantId(),members.current(actor).memberId(),id));validate(coupon,store);if(money(gross).compareTo(money(coupon.minimumSpend()))<0)throw conflict("未达到券门槛");return coupon;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void reserve(Actor actor,String order,String store,Application selected){if(selected==null)return;var locked=Inputs.found(mapper.lock(actor.tenantId(),members.current(actor).memberId(),selected.couponId()));
        // 定义不可变只需普通读，避免锁券后反向锁定义与领取形成死锁。
        var d=Inputs.found(mapper.definitionFind(actor.tenantId(),locked.definitionId(),locked.version()));
        var coupon=new Coupon(locked.couponId(),locked.definitionId(),locked.version(),locked.memberId(),d.storeId(),d.name(),locked.status(),d.discountAmount(),d.minimumSpend(),locked.validFrom()==null?d.validFrom():locked.validFrom(),locked.validTo(),d.stackable(),d.platformFundingBps());validate(coupon,store);
        if(!coupon.definitionId().equals(selected.definitionId())||coupon.version()!=selected.version()||coupon.platformFundingBps()!=selected.platformFundingBps()||money(selected.discount()).compareTo(money(coupon.discountAmount()))>0||money(selected.discount()).compareTo(Money.ZERO)<=0)throw conflict("券快照不匹配");
        if(mapper.reserve(actor.tenantId(),coupon.couponId(),order)!=1)throw conflict("券已被其他订单占用");mapper.insertHold(actor.tenantId(),order,coupon.couponId(),selected.discount());
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void confirm(String tenant,String order){finish(tenant,order,"HELD","USED","USED");}
    @Transactional(propagation=Propagation.MANDATORY)
    public void release(String tenant,String order){finish(tenant,order,"HELD","AVAILABLE","RELEASED");}
    @Transactional(propagation=Propagation.MANDATORY)
    public void refund(String tenant,String order){finish(tenant,order,"USED","AVAILABLE","REFUNDED");}
    private void finish(String tenant,String order,String expected,String target,String holdTarget){var hold=mapper.hold(tenant,order);if(hold==null||hold.status().equals(holdTarget))return;if(!hold.status().equals(expected))throw conflict("券占用终态冲突");
        if(mapper.finish(tenant,order,hold.couponId(),expected,target)!=1||mapper.holdStatus(tenant,order,expected,holdTarget)!=1)throw conflict("券状态并发冲突");
    }
    private void validate(Coupon coupon,String store){if(!coupon.storeId().equals(store)||!coupon.status().equals("AVAILABLE")||clock.instant().isBefore(coupon.validFrom())||!clock.instant().isBefore(coupon.validTo()))throw conflict("券不属于本店、已占用或失效");}
    private DefinitionView definition(CouponMapper.DefinitionRow r){return new DefinitionView(new Definition(r.definitionId(),r.version(),r.storeId(),r.name(),r.minimumSpend(),r.discountAmount(),r.validFrom(),r.validTo(),r.quota(),r.stackable(),r.platformFundingBps(),r.issuanceMode(),r.validityDays()),r.issued());}
    private Money money(String value){Inputs.text(value,32);try{return new Money(new BigDecimal(value));}catch(NumberFormatException ex){throw new DomainException(DomainException.Code.INVALID_INPUT,"金额格式无效");}}
    private DomainException conflict(String message){return new DomainException(DomainException.Code.CONFLICT,message);}
}
