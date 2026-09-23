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
    public DefinitionView create(Actor actor,String key,Definition input){actor.requireAdmin();Inputs.require(input!=null&&input.version()>0&&input.quota()>0&&input.quota()<=1000000&&input.validFrom()!=null&&input.validTo()!=null&&input.validFrom().isBefore(input.validTo()),"券定义无效");Identifiers.require(input.definitionId());Inputs.text(input.name(),128);money(input.minimumSpend());Inputs.require(money(input.discountAmount()).compareTo(Money.ZERO)>0,"券金额必须大于零");return commands.run(actor,"coupon.definition",key,input,DefinitionView.class,()->{stores.requireActive(actor,input.storeId());mapper.definition(actor.tenantId(),input);return definition(mapper.definitionFind(actor.tenantId(),input.definitionId(),input.version()));});}
    /** 消费者可见本店定义，但领取仍需服务端资格校验。 */
    public List<DefinitionView> definitions(Actor actor,String store,String after,int limit){stores.requireActive(actor,store);Inputs.page(after,limit);return mapper.definitions(actor.tenantId(),store,after,limit).stream().map(this::definition).toList();}
    /** 锁定义序列化配额，当前读检查是否已领，重试不能多占额度。 */
    public Coupon claim(Actor actor,String key,String id,long version){Identifiers.require(id);Inputs.require(version>0,"券版本无效");return commands.run(actor,"coupon.claim",key,List.of(id,version),Coupon.class,()->{
        var member=members.current(actor);members.requireActive(actor,member.memberId());var definition=Inputs.found(mapper.definitionLock(actor.tenantId(),id,version));stores.requireActive(actor,definition.storeId());
        var existing=mapper.existing(actor.tenantId(),member.memberId(),id,version);if(existing!=null)return existing;
        if(clock.instant().isBefore(definition.validFrom())||!clock.instant().isBefore(definition.validTo())||mapper.issue(actor.tenantId(),id,version)!=1)throw conflict("券未生效、已过期或配额不足");
        String coupon=UUID.randomUUID().toString();mapper.coupon(actor.tenantId(),member.memberId(),coupon,definition);return mapper.find(actor.tenantId(),member.memberId(),coupon);
    });}
    public List<Coupon> wallet(Actor actor,String after,int limit){Inputs.page(after,limit);return mapper.wallet(actor.tenantId(),members.current(actor).memberId(),after,limit);}
    /** 报价只校验资格，不消费券，最终占用发生在下单事务。 */
    public Coupon eligible(Actor actor,String id,String store,String gross){Identifiers.require(id);var coupon=Inputs.found(mapper.find(actor.tenantId(),members.current(actor).memberId(),id));validate(coupon,store);if(money(gross).compareTo(money(coupon.minimumSpend()))<0)throw conflict("未达到券门槛");return coupon;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void reserve(Actor actor,String order,String store,Application selected){if(selected==null)return;var locked=Inputs.found(mapper.lock(actor.tenantId(),members.current(actor).memberId(),selected.couponId()));
        // 定义不可变只需普通读，避免锁券后反向锁定义与领取形成死锁。
        var d=Inputs.found(mapper.definitionFind(actor.tenantId(),locked.definitionId(),locked.version()));
        var coupon=new Coupon(locked.couponId(),locked.definitionId(),locked.version(),locked.memberId(),d.storeId(),d.name(),locked.status(),d.discountAmount(),d.minimumSpend(),d.validFrom(),locked.validTo(),d.stackable());validate(coupon,store);
        if(!coupon.definitionId().equals(selected.definitionId())||coupon.version()!=selected.version()||money(selected.discount()).compareTo(money(coupon.discountAmount()))>0||money(selected.discount()).compareTo(Money.ZERO)<=0)throw conflict("券快照不匹配");
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
    private DefinitionView definition(CouponMapper.DefinitionRow r){return new DefinitionView(new Definition(r.definitionId(),r.version(),r.storeId(),r.name(),r.minimumSpend(),r.discountAmount(),r.validFrom(),r.validTo(),r.quota(),r.stackable()),r.issued());}
    private Money money(String value){Inputs.text(value,32);try{return new Money(new BigDecimal(value));}catch(NumberFormatException ex){throw new DomainException(DomainException.Code.INVALID_INPUT,"金额格式无效");}}
    private DomainException conflict(String message){return new DomainException(DomainException.Code.CONFLICT,message);}
}
