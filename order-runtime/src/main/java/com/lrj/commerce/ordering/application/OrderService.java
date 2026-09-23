package com.lrj.commerce.ordering.application;
import com.lrj.commerce.ordering.api.OrderApi;
import com.lrj.commerce.ordering.infrastructure.*;
import com.lrj.commerce.order.api.*;
import com.lrj.commerce.order.domain.OrderLifecycle;
import com.lrj.commerce.trade.api.QuoteApi;
import com.lrj.commerce.inventory.api.InventoryApi;
import com.lrj.commerce.member.api.MemberApi;
import com.lrj.commerce.store.api.StoreApi;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 订单用例编排同库端口，远程支付/物流不进入这段事务。 */
@Service
public class OrderService implements OrderApi {
    private final com.lrj.commerce.member.api.PointsSpendApi points;private final com.lrj.commerce.benefit.api.EntitlementApi entitlements;private final com.lrj.commerce.campaign.api.CampaignFundingApi funding;private final com.lrj.commerce.benefit.api.CouponApi coupons;private final OrderMapper mapper;private final Commands commands;private final QuoteApi quotes;private final InventoryApi inventory;
    private final MemberApi members;private final StoreApi stores;private final Outbox outbox;private final AddressCipher addresses;private final Clock clock;
    public OrderService(OrderMapper mapper,Commands commands,QuoteApi quotes,InventoryApi inventory,MemberApi members,StoreApi stores,Outbox outbox,AddressCipher addresses,Clock clock,com.lrj.commerce.benefit.api.CouponApi coupons,com.lrj.commerce.campaign.api.CampaignFundingApi funding,com.lrj.commerce.benefit.api.EntitlementApi entitlements,com.lrj.commerce.member.api.PointsSpendApi points) {this.points=points;this.entitlements=entitlements;this.funding=funding;this.coupons=coupons;
        this.mapper=mapper;this.commands=commands;this.quotes=quotes;this.inventory=inventory;this.members=members;this.stores=stores;this.outbox=outbox;this.addresses=addresses;this.clock=clock;
    }
    /** 订单编号由服务端生成，失败预占会回滚报价消费，允许重试原命令。 */
    public View create(Actor actor,String key,Create input) {
        Inputs.require(input!=null&&input.address()!=null,"实物订单需要收货地址");Identifiers.require(input.quoteId());
        Inputs.text(input.address().recipient(),128);Inputs.text(input.address().phone(),32);Inputs.text(input.address().detail(),512);
        return commands.run(actor,"order.create",key,input,View.class,()->{
            var member=members.current(actor);members.requireActive(actor,member.memberId());
            String id=UUID.randomUUID().toString();var quote=quotes.consume(actor,input.quoteId(),id);stores.requireActive(actor,quote.storeId());
            points.reserve(actor,id,member.memberId(),quote.points(),new BigDecimal(quote.payable()).add(quote.points()==null?BigDecimal.ZERO:new BigDecimal(quote.points().discount())).toPlainString());
            coupons.reserve(actor,id,quote.storeId(),quote.coupon());funding.reserve(actor,id,quote.storeId(),quote.promotion());
            entitlements.reserveOrder(actor,id,member.memberId(),quote.storeId(),quote.promotion()==null?null:quote.promotion().grant());
            for(var line:quote.items().stream().sorted(Comparator.comparing(QuoteApi.Line::skuId)).toList()) inventory.reserve(actor,id,quote.storeId(),line.skuId(),line.quantity());
            var lifecycle=OrderLifecycle.start();boolean free=new BigDecimal(quote.payable()).signum()==0;
            if(free) {lifecycle=lifecycle.apply(OrderEvent.PAYMENT_CONFIRMED);points.confirm(actor.tenantId(),id,member.memberId());inventory.confirm(actor.tenantId(),id);coupons.confirm(actor.tenantId(),id);funding.confirm(actor.tenantId(),id);entitlements.confirmOrder(actor.tenantId(),id);}
            var now=clock.instant().truncatedTo(ChronoUnit.MILLIS);
            var view=new View(id,member.memberId(),quote.storeId(),quote.merchantId(),quote.quoteId(),quote.payable(),lifecycle.state().name(),free?"NO_PAYMENT_REQUIRED":"CHANNEL_REQUIRED",lifecycle.version(),now,now.plusSeconds(900),quote.items());
            mapper.insert(actor.tenantId(),view,JsonCodec.write(view.items()),addresses.encrypt(actor.tenantId(),id,input.address()));
            outbox.append(actor.tenantId(),"order.created.v1",id,view.version(),view);
            // 零元单可以履约，但不能伪造OrderPaid渠道收款事实。
            if(free) outbox.append(actor.tenantId(),"order.ready.v1",id,view.version(),view);
            return view;
        });
    }
    /** 读取时只按可信会员归属查询。 */
    public View read(Actor actor,String id) {Identifiers.require(id);return view(Inputs.found(mapper.read(actor.tenantId(),members.current(actor).memberId(),id)));}
    /** 列表不加载明细快照，防止列表隐含N+1或放大响应。 */
    public List<View> list(Actor actor,String after,int limit) {Inputs.page(after,limit);return mapper.list(actor.tenantId(),members.current(actor).memberId(),after,limit).stream().map(this::view).toList();}
    /** 未开始支付可以直接释放；重复取消从持久化状态回放，不重复发事件。 */
    public View cancel(Actor actor,String key,String id) {
        Identifiers.require(id);
        return commands.run(actor,"order.cancel",key,id,View.class,()->{
            var row=Inputs.found(mapper.lock(actor.tenantId(),members.current(actor).memberId(),id));
            var state=OrderState.valueOf(row.status());
            if(state==OrderState.CANCELLED||state==OrderState.CLOSING) return view(row);
            var next=new OrderLifecycle(state,row.version()).apply(OrderEvent.REQUEST_CANCEL);
            if(mapper.change(actor.tenantId(),id,row.version(),next.state().name())!=1) throw new DomainException(DomainException.Code.CONFLICT,"订单并发版本冲突");
            if(next.state()==OrderState.CANCELLED){points.release(actor.tenantId(),id,row.memberId());inventory.release(actor.tenantId(),id);coupons.release(actor.tenantId(),id);funding.release(actor.tenantId(),id);entitlements.releaseOrder(actor.tenantId(),id);}
            var result=view(mapper.read(actor.tenantId(),row.memberId(),id));
            outbox.append(actor.tenantId(),next.state()==OrderState.CANCELLED?"order.cancelled.v1":"order.closing.v1",id,result.version(),result);
            return result;
        });
    }
    private View view(OrderMapper.Row r) {
        List<QuoteApi.Line> items=r.itemsJson()==null?List.of():Arrays.asList(JsonCodec.read(r.itemsJson(),QuoteApi.Line[].class));
        return new View(r.orderId(),r.memberId(),r.storeId(),r.merchantId(),r.quoteId(),r.payable(),r.status(),r.paymentKind(),r.version(),r.createdAt(),r.expiresAt(),items);
    }
    /** 同事务锁住订单，取消/启动支付只能有一个先发生。 */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public View beginPayment(Actor actor,String id) {
        var row=Inputs.found(mapper.lock(actor.tenantId(),members.current(actor).memberId(),id));
        if(row.status().equals("PAYMENT_IN_PROGRESS")) return view(row);
        Inputs.require(row.paymentKind().equals("CHANNEL_REQUIRED"),"零元订单无需渠道支付");
        if(!clock.instant().isBefore(row.expiresAt())) throw new DomainException(DomainException.Code.CONFLICT,"订单已过期");
        transition(actor.tenantId(),row,OrderEvent.START_PAYMENT);return view(mapper.internalRead(actor.tenantId(),id));
    }
    /** 只有渠道已证实的金额匹配事实才能改变订单与库存。 */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public View paymentFact(String tenant,String id,String amount,boolean paid) {
        var row=Inputs.found(mapper.internalLock(tenant,id));
        if(new BigDecimal(row.payable()).compareTo(new BigDecimal(amount))!=0) throw new DomainException(DomainException.Code.CONFLICT,"支付金额不匹配");
        if((paid&&Set.of("PAID","FULFILLING","COMPLETED").contains(row.status()))||(!paid&&row.status().equals("CANCELLED"))) return view(row);
        transition(tenant,row,paid?OrderEvent.PAYMENT_CONFIRMED:OrderEvent.PAYMENT_ABSENCE_CONFIRMED);
        if(paid){points.confirm(tenant,id,row.memberId());inventory.confirm(tenant,id);coupons.confirm(tenant,id);funding.confirm(tenant,id);entitlements.confirmOrder(tenant,id);}else{points.release(tenant,id,row.memberId());inventory.release(tenant,id);coupons.release(tenant,id);funding.release(tenant,id);entitlements.releaseOrder(tenant,id);}
        var result=view(mapper.internalRead(tenant,id));outbox.append(tenant,paid?"order.paid.v1":"order.cancelled.v1",id,result.version(),result);return result;
    }
    /** 跨域只返回API投影，其他模块不读订单Mapper。 */
    public View internalRead(String tenant,String id) {return view(Inputs.found(mapper.internalRead(tenant,id)));}
    /** 批量任务每次最多20单，超时不是未支付证明。 */
    public int expire(Actor actor,String key) {
        actor.requireAdmin();return commands.run(actor,"order.expire",key,"expire",Integer.class,()->{
            var rows=mapper.expired(actor.tenantId(),clock.instant());
            for(var row:rows) {
                var next=transition(actor.tenantId(),row,OrderEvent.REQUEST_CANCEL);
                if(next.state()==OrderState.CANCELLED){points.release(actor.tenantId(),row.orderId(),row.memberId());inventory.release(actor.tenantId(),row.orderId());coupons.release(actor.tenantId(),row.orderId());funding.release(actor.tenantId(),row.orderId());entitlements.releaseOrder(actor.tenantId(),row.orderId());}
                var result=view(mapper.internalRead(actor.tenantId(),row.orderId()));
                outbox.append(actor.tenantId(),next.state()==OrderState.CANCELLED?"order.cancelled.v1":"order.closing.v1",row.orderId(),result.version(),result);
            }
            return rows.size();
        });
    }
    private OrderLifecycle transition(String tenant,OrderMapper.Row row,OrderEvent event) {
        var next=new OrderLifecycle(OrderState.valueOf(row.status()),row.version()).apply(event);
        if(mapper.change(tenant,row.orderId(),row.version(),next.state().name())!=1)throw new DomainException(DomainException.Code.CONFLICT,"订单并发版本冲突");return next;
    }
    /** 物流事实不倒退订单；重复由调用者与当前终态共同去重。 */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public View fulfillmentFact(String tenant,String id,boolean delivered){
        var row=Inputs.found(mapper.internalLock(tenant,id));String target=delivered?"COMPLETED":"FULFILLING";
        if(row.status().equals(target)||(!delivered&&row.status().equals("COMPLETED")))return view(row);
        transition(tenant,row,delivered?OrderEvent.CONFIRM_DELIVERY:OrderEvent.START_FULFILLMENT);
        var result=view(mapper.internalRead(tenant,id));outbox.append(tenant,delivered?"order.completed.v1":"order.fulfilling.v1",id,result.version(),result);return result;
    }
    /** 与本人列表分开，不能通过可选参数放大会员查询范围。 */
    public List<View> adminList(Actor actor,String after,int limit){actor.requireAdmin();Inputs.page(after,limit);return mapper.list(actor.tenantId(),null,after,limit).stream().map(this::view).toList();}
    /** 管理员只读取本租户商业快照，不返回加密地址。 */
    public View adminRead(Actor actor,String id){actor.requireAdmin();Identifiers.require(id);return internalRead(actor.tenantId(),id);}
}
