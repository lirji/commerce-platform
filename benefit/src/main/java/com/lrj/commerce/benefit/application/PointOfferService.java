package com.lrj.commerce.benefit.application;
import com.lrj.commerce.benefit.api.*;
import com.lrj.commerce.benefit.infrastructure.PointOfferMapper;
import com.lrj.commerce.member.api.*;
import com.lrj.commerce.store.api.StoreApi;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
/** 扣分、限额、来源发放和回执在一个事务中，资产不足不能扣走积分。 */
@Service
public class PointOfferService implements PointOfferApi {
    private final PointOfferMapper mapper;private final MemberApi members;private final MemberPointsApi points;
    private final CouponApi coupons;private final EntitlementApi entitlements;private final StoreApi stores;
    private final Commands commands;private final Clock clock;
    public PointOfferService(PointOfferMapper mapper,MemberApi members,MemberPointsApi points,CouponApi coupons,EntitlementApi entitlements,StoreApi stores,Commands commands,Clock clock){this.mapper=mapper;this.members=members;this.points=points;this.coupons=coupons;this.entitlements=entitlements;this.stores=stores;this.commands=commands;this.clock=clock;}
    /** 资产与价格不可变，更换玩法应创建新兑换项目。 */
    public View create(Actor actor,String key,Offer input){
        actor.requireAdmin();Inputs.require(input!=null && input.kind()!=null && input.assetVersion()>0,"兑换资产无效");
        Identifiers.require(input.offerId());Identifiers.require(input.assetId());Inputs.text(input.name(),128);
        Inputs.require(input.points()>0 && input.points()<=1_000_000_000L && input.quota()>0 && input.quota()<=1000000 && input.perMemberLimit()>0 && input.perMemberLimit()<=1000,"兑换积分或限额无效");
        Inputs.require(input.validFrom()!=null && input.validTo()!=null && input.validTo().isAfter(input.validFrom()),"兑换窗口无效");
        var value=new Offer(input.offerId(),input.storeId(),input.name(),input.kind(),input.assetId(),input.assetVersion(),input.points(),input.quota(),input.perMemberLimit(),input.validFrom().truncatedTo(ChronoUnit.MILLIS),input.validTo().truncatedTo(ChronoUnit.MILLIS));
        return commands.run(actor,"points.offer.create",key,value,View.class,()->{
            stores.requireActive(actor,value.storeId());
            if(value.kind()==Kind.COUPON)coupons.validateExchange(actor.tenantId(),value.storeId(),value.assetId(),value.assetVersion(),value.validFrom(),value.validTo());
            else entitlements.validateBinding(actor.tenantId(),value.storeId(),new EntitlementApi.Ref(value.assetId(),value.assetVersion()),value.validFrom(),value.validTo());
            mapper.insert(actor.tenantId(),value,JsonCodec.write(value));return view(mapper.find(actor.tenantId(),value.offerId()));
        });
    }
    /** 不删除已发权益，状态修改只影响新兑换。 */
    public View status(Actor actor,String key,String id,Status input){
        actor.requireAdmin();Identifiers.require(id);Inputs.require(input!=null && input.expectedVersion()>=0,"版本无效");Inputs.text(input.reason(),256);
        return commands.run(actor,"points.offer.status",key,new Object[]{id,input},View.class,()->{
            Inputs.found(mapper.lock(actor.tenantId(),id));if(mapper.status(actor.tenantId(),id,input)!=1)throw conflict("兑换项目版本已变化");return view(mapper.lock(actor.tenantId(),id));
        });
    }
    /** 受控目录不泄露其他租户或失效活动。 */
    public List<View> list(Actor actor,String store,String after,int limit){
        if(actor.role()!=Actor.Role.ADMIN && actor.role()!=Actor.Role.MEMBER)throw new DomainException(DomainException.Code.FORBIDDEN,"无兑换目录权限");
        Inputs.page(after,limit);stores.requireActive(actor,store);return mapper.list(actor.tenantId(),store,after,limit,actor.role()==Actor.Role.ADMIN,clock.instant()).stream().map(this::view).toList();
    }
    /** 先锁会员再锁项目，避免不同入口对会员和资产的反向持锁。 */
    public Receipt redeem(Actor actor,String key,String id){
        requireMember(actor);Identifiers.require(id);
        return commands.run(actor,"points.offer.redeem",key,id,Receipt.class,()->{
            var member=members.current(actor);var initial=view(Inputs.found(mapper.find(actor.tenantId(),id)));
            stores.requireActive(actor,initial.content().storeId());String source=UUID.randomUUID().toString();
            points.exchange(actor,source,initial.content().points());
            var locked=view(Inputs.found(mapper.lock(actor.tenantId(),id)));var offer=locked.content();
            if(!locked.status().equals("ACTIVE") || clock.instant().isBefore(offer.validFrom()) || !clock.instant().isBefore(offer.validTo()))throw conflict("兑换项目已停用或不在有效期内");
            var used=mapper.count(actor.tenantId(),id,member.memberId());
            if((used!=null && used>=offer.perMemberLimit()) || mapper.issue(actor.tenantId(),id)!=1)throw conflict("兑换次数或总额度已用完");
            mapper.memberIssue(actor.tenantId(),id,member.memberId());
            String asset=offer.kind()==Kind.COUPON ? coupons.grantFromPoints(actor.tenantId(),member.memberId(),offer.storeId(),source,offer.assetId(),offer.assetVersion()).couponId()
                : entitlements.grantFromPoints(actor.tenantId(),member.memberId(),offer.storeId(),source,new EntitlementApi.Ref(offer.assetId(),offer.assetVersion())).grantId();
            var receipt=new Receipt(source,id,member.memberId(),offer.points(),offer.kind(),asset,clock.instant().truncatedTo(ChronoUnit.MILLIS));
            mapper.receipt(actor.tenantId(),receipt);return receipt;
        });
    }
    /** 回执不接受任意会员参数。 */
    public List<Receipt> receipts(Actor actor,String after,int limit){requireMember(actor);Inputs.page(after,limit);return mapper.receipts(actor.tenantId(),members.current(actor).memberId(),after,limit);}
    private void requireMember(Actor actor){if(actor.role()!=Actor.Role.MEMBER)throw new DomainException(DomainException.Code.FORBIDDEN,"仅会员可兑换或读取本人回执");}
    private View view(PointOfferMapper.Row row){return new View(JsonCodec.read(row.contentJson(),Offer.class),row.status(),row.issued(),row.version());}
    private DomainException conflict(String reason){return new DomainException(DomainException.Code.CONFLICT,reason);}
}
