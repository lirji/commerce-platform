package com.lrj.commerce.trade.application;
import com.lrj.commerce.trade.api.QuoteApi;
import com.lrj.commerce.trade.infrastructure.QuoteMapper;
import com.lrj.commerce.member.api.MemberApi;
import com.lrj.commerce.store.api.StoreApi;
import com.lrj.commerce.catalog.api.CatalogApi;
import com.lrj.commerce.campaign.api.CampaignApi;
import com.lrj.commerce.marketing.api.*;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;

/** 报价编排只消费各领域API，服务端快照与命令结果同事务提交。 */
@Service
public class QuoteService implements QuoteApi {
    private final com.lrj.commerce.benefit.api.CouponApi coupons;private final QuoteMapper mapper;private final Commands commands;private final MemberApi members;private final StoreApi stores;
    private final CatalogApi catalog;private final CampaignApi campaigns;private final DecisionPort decisions;private final Clock clock;
    public QuoteService(QuoteMapper mapper,Commands commands,MemberApi members,StoreApi stores,CatalogApi catalog,CampaignApi campaigns,DecisionPort decisions,Clock clock,com.lrj.commerce.benefit.api.CouponApi coupons) {this.coupons=coupons;
        this.mapper=mapper;this.commands=commands;this.members=members;this.stores=stores;this.catalog=catalog;this.campaigns=campaigns;this.decisions=decisions;this.clock=clock;
    }
    /** 先规范化购物清单；同键换序或拆分同一SKU数量不产生第二张报价。 */
    public View create(Actor actor,String key,Request input) {
        Inputs.require(input!=null&&input.items()!=null&&!input.items().isEmpty()&&input.items().size()<=100,"购物清单必须为1至100项");
        Identifiers.require(input.storeId());
        SortedMap<String,Integer> quantities=new TreeMap<>();
        for(var item:input.items()) {
            Inputs.require(item!=null&&item.quantity()>=1&&item.quantity()<=10000,"购买数量无效");Identifiers.require(item.skuId());
            int count=quantities.getOrDefault(item.skuId(),0)+item.quantity();Inputs.require(count<=10000,"合并购买数量超限");quantities.put(item.skuId(),count);
        }
        var normalized=new Request(input.storeId(),quantities.entrySet().stream().map(e->new Selection(e.getKey(),e.getValue())).toList(),input.couponId());
        return commands.run(actor,"quote.create",key,normalized,View.class,()->{
            var member=members.current(actor);members.requireActive(actor,member.memberId());
            var store=stores.requireActive(actor,input.storeId());
            var skus=catalog.published(actor,input.storeId(),new ArrayList<>(quantities.keySet()));
            var lines=skus.stream().map(s->new DecisionModels.Line(s.skuId(),s.skuId(),new Money(new BigDecimal(s.unitPrice())),quantities.get(s.skuId()))).toList();
            var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
            var candidates=campaigns.candidates(actor,store.storeId(),member.memberId(),now);
            var gross=lines.stream().map(l->l.unitPrice().multiply(l.quantity())).reduce(Money.ZERO,Money::add);
            var priced=decisions.decide(new DecisionModels.Request(new DecisionModels.Scope(actor.tenantId(),store.merchantId(),store.storeId()),member.memberId(),now,lines,
                Map.of("memberLevel",new Fact.Text(member.memberLevel()),"orderAmount",new Fact.Decimal(gross.amount())),candidates.offers()));
            var selectedCampaign=priced.selected();Money campaignDiscount=priced.discount();Money couponDiscount=Money.ZERO;
            com.lrj.commerce.benefit.api.CouponApi.Application couponApplication=null;String couponStatus="NOT_REQUESTED";
            var expires=now.plusSeconds(300);
            if(input.couponId()!=null){
                var coupon=coupons.eligible(actor,input.couponId(),store.storeId(),gross.amount().toPlainString());Money couponValue=new Money(new BigDecimal(coupon.discountAmount()));
                if(coupon.stackable()){Money remaining=gross.subtract(campaignDiscount);couponDiscount=couponValue.compareTo(remaining)>0?remaining:couponValue;}
                else {Money actual=couponValue.compareTo(gross)>0?gross:couponValue;if(actual.compareTo(campaignDiscount)>0){couponDiscount=actual;campaignDiscount=Money.ZERO;selectedCampaign=null;}}
                couponStatus=couponDiscount.compareTo(Money.ZERO)>0?"APPLIED":"NOT_SELECTED";
                if(couponDiscount.compareTo(Money.ZERO)>0){couponApplication=new com.lrj.commerce.benefit.api.CouponApi.Application(coupon.couponId(),coupon.definitionId(),coupon.version(),couponDiscount.amount().toPlainString());if(coupon.validTo().isBefore(expires))expires=coupon.validTo();}
            }
            Money totalDiscount=campaignDiscount.add(couponDiscount);var allocations=decisions.allocate(lines,totalDiscount);
            var skuById=new HashMap<String,CatalogApi.View>();skus.forEach(sku->skuById.put(sku.skuId(),sku));
            var resultLines=allocations.stream().map(l->{var sku=skuById.get(l.skuId());return new Line(sku.skuId(),sku.revision(),sku.title(),quantities.get(sku.skuId()),sku.unitPrice(),l.gross().amount().toPlainString(),l.discount().amount().toPlainString(),l.payable().amount().toPlainString());}).toList();
            var result=new View(UUID.randomUUID().toString(),member.memberId(),store.merchantId(),store.storeId(),"CNY",gross.amount().toPlainString(),totalDiscount.amount().toPlainString(),gross.subtract(totalDiscount).amount().toPlainString(),now,expires,resultLines,selectedCampaign,priced.trace(),candidates.sources(),couponApplication,campaignDiscount.amount().toPlainString(),couponStatus);
            mapper.insert(actor.tenantId(),result,JsonCodec.write(result));return result;
        });
    }
    /** 历史快照不依赖当前价格/活动，冻结会员仍可查本人的历史报价。 */
    public View read(Actor actor,String id) {
        Identifiers.require(id);var member=members.current(actor);
        return JsonCodec.read(Inputs.found(mapper.read(actor.tenantId(),member.memberId(),id)),View.class);
    }
    /** 锁后取当前时间验证TTL，不能在锁等待前通过一次检查就消费过期报价。 */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public View consume(Actor actor,String id,String orderId) {
        Identifiers.require(id);Identifiers.require(orderId);var member=members.current(actor);
        var locked=Inputs.found(mapper.lock(actor.tenantId(),member.memberId(),id));
        if(locked.consumedOrderId()!=null||!clock.instant().isBefore(locked.expiresAt())) throw new DomainException(DomainException.Code.CONFLICT,"报价已消费或过期");
        if(mapper.consume(actor.tenantId(),id,orderId)!=1) throw new DomainException(DomainException.Code.CONFLICT,"报价消费冲突");
        return JsonCodec.read(locked.snapshotJson(),View.class);
    }
}
