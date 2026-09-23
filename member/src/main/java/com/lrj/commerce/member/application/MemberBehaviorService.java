package com.lrj.commerce.member.application;
import com.lrj.commerce.member.api.*;
import com.lrj.commerce.member.infrastructure.*;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
/** 行为限额与会员锁一致，聚合事实不采信客户端金额或身份字段。 */
@Service
public class MemberBehaviorService implements MemberBehaviorApi {
    private final BehaviorMapper mapper;private final GrowthMapper locks;private final MemberMapper members;private final Commands commands;private final Clock clock;
    public MemberBehaviorService(BehaviorMapper mapper,GrowthMapper locks,MemberMapper members,Commands commands,Clock clock){this.mapper=mapper;this.locks=locks;this.members=members;this.commands=commands;this.clock=clock;}
    /** 没有消费事实时保留null，不伪造流失天数。 */
    public Detail detail(Actor actor,String member){var value=authorize(actor,member);return new Detail(value,profile(actor.tenantId(),member),facts(actor.tenantId(),List.of(member),clock.instant()).getFirst());}
    /** 月日合法性用闰年校验，后续由UTC实际日期决定是否生日。 */
    public Profile profile(Actor actor,String key,String member,ProfileChange input){
        authorize(actor,member);Inputs.require(input!=null && input.expectedVersion()>=0,"资料版本无效");Inputs.text(input.reason(),256);
        if(input.birthday()!=null){try{Inputs.require(input.birthday().matches("[0-9]{2}-[0-9]{2}"),"生日应为MM-DD");MonthDay.parse("--"+input.birthday());}catch(DateTimeException e){throw new DomainException(DomainException.Code.INVALID_INPUT,"生日月日无效");}}
        return commands.run(actor,"member.behavior.profile",key,new Object[]{member,input},Profile.class,()->{
            var locked=Inputs.found(locks.lockMember(actor.tenantId(),member));if(!locked.status().equals("ACTIVE"))throw conflict("非正常会员不可修改偏好");mapper.ensure(actor.tenantId(),member);
            if(mapper.profileChange(actor.tenantId(),member,input)!=1)throw conflict("会员偏好版本已变化");return profile(actor.tenantId(),member);
        });
    }
    /** 交互去重范围包含会员；每个会员拥有独立日额度，不阻塞其他会员。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public Event record(Actor actor,Signal input){
        if(actor.role()!=Actor.Role.MEMBER)throw new DomainException(DomainException.Code.FORBIDDEN,"仅会员可记录本人交互");
        Inputs.require(input!=null && input.kind()!=null,"行为类型缺失");Identifiers.require(input.eventId());Identifiers.require(input.storeId());Identifiers.require(input.skuId());
            var member=Inputs.found(members.byActor(actor.tenantId(),actor.actorId()));var locked=Inputs.found(locks.lockMember(actor.tenantId(),member.memberId()));
            if(!locked.status().equals("ACTIVE"))throw conflict("会员状态不可记录交互");
            var old=mapper.event(actor.tenantId(),member.memberId(),input.eventId());
            if(old!=null){if(old.kind()!=input.kind()||!old.storeId().equals(input.storeId())||!old.skuId().equals(input.skuId()))throw conflict("行为来源已绑定其他内容");return old;}
            Instant at=clock.instant().truncatedTo(ChronoUnit.MILLIS);LocalDate day=at.atZone(ZoneOffset.UTC).toLocalDate();
            var count=mapper.dayCount(actor.tenantId(),member.memberId(),day);if(count!=null && count>=200)throw conflict("今日交互记录已达上限");
            mapper.eventInsert(actor.tenantId(),member.memberId(),input,at);mapper.dayAdd(actor.tenantId(),member.memberId(),day,input.kind(),at);
            return mapper.event(actor.tenantId(),member.memberId(),input.eventId());
    }
    /** 正序游标与租户/会员索引一致。 */
    public List<Event> events(Actor actor,String member,long after,int limit){authorize(actor,member);Inputs.require(after>=0,"行为游标无效");Inputs.page("",limit);return mapper.events(actor.tenantId(),member,after,limit);}
    /** 先从权威来源识别会员，会员锁后当前读取净额防止回放覆盖新退款。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void projectOrder(String tenant,String order,Instant orderedAt){
        Identifiers.require(tenant);Identifiers.require(order);Inputs.require(orderedAt!=null,"原订单时间缺失");
        // 调用方成长处理已经持会员锁；补建使用来源定位后再按同一顺序加锁。
        var identity=mapper.sourceMember(tenant,order);if(identity==null)return;
        locks.lockMember(tenant,identity);var source=mapper.source(tenant,order);mapper.project(tenant,order,source,orderedAt);
    }
    /** 不能使用先于会员锁建立的RR快照决定是否允许触达。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean journeyAllowed(String tenant,String member){
        var value=locks.lockMember(tenant,member);if(value==null||!value.status().equals("ACTIVE"))return false;
        var preference=mapper.profileCurrent(tenant,member);return preference==null||preference.journeyEnabled();
    }
    /** 门店维度避免把其他门店的加购误当成本店意向。 */
    public Instant latestCart(String tenant,String member,String store){Identifiers.require(tenant);Identifiers.require(member);Identifiers.require(store);return mapper.latestCart(tenant,member,store);}
    /** 每次最多100会员，一条SQL聚合日汇总和订单窗口，避免网络N+1。 */
    public List<Facts> facts(String tenant,List<String> ids,Instant now){
        Identifiers.require(tenant);Inputs.require(ids!=null && ids.size()<=100 && now!=null,"行为事实批次超限");if(ids.isEmpty())return List.of();ids.forEach(Identifiers::require);
        LocalDate today=now.atZone(ZoneOffset.UTC).toLocalDate(),start=today.minusDays(29);String birthday=MonthDay.from(today).toString().substring(2);
        return mapper.facts(tenant,ids,start,today,start.atStartOfDay(ZoneOffset.UTC).toInstant(),now).stream().map(r->new Facts(r.memberId(),r.browse30(),r.cart30(),r.completedOrders30(),r.netSpend30(),r.lastOrderAt(),r.lastCartAt(),r.lastOrderAt()==null?null:Math.max(0,ChronoUnit.DAYS.between(r.lastOrderAt(),now)),Math.max(0,ChronoUnit.DAYS.between(r.joinedAt(),now)),birthday.equals(r.birthday()),r.journeyEnabled())).toList();
    }
    private Profile profile(String tenant,String member){var p=mapper.profile(tenant,member);return p==null?new Profile(null,true,0):p;}
    private MemberApi.View authorize(Actor actor,String member){Identifiers.require(member);var value=Inputs.found(members.find(actor.tenantId(),member));if(actor.role()!=Actor.Role.ADMIN && (actor.role()!=Actor.Role.MEMBER || !value.actorId().equals(actor.actorId())))throw new DomainException(DomainException.Code.FORBIDDEN,"不可访问其他会员行为");return value;}
    private DomainException conflict(String reason){return new DomainException(DomainException.Code.CONFLICT,reason);}
}
