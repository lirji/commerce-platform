package com.lrj.commerce.aftersales.application;
import com.lrj.commerce.aftersales.api.AftersaleApi;
import com.lrj.commerce.aftersales.infrastructure.AftersaleMapper;
import com.lrj.commerce.ordering.api.OrderApi;
import com.lrj.commerce.fulfillment.api.FulfillmentApi;
import com.lrj.commerce.payment.api.RefundApi;
import com.lrj.commerce.inventory.api.InventoryApi;
import com.lrj.commerce.member.api.MemberApi;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import java.math.*;
import java.util.*;
/** 申请、退货、退款三个事实分开，只有可信退款成功消费后才完成售后。 */
@Service
public class AftersaleService implements AftersaleApi,EventHandler {
    private final com.lrj.commerce.member.api.PointsSpendApi points;private final AftersaleMapper mapper;private final OrderApi orders;private final FulfillmentApi fulfillment;private final RefundApi refunds;private final InventoryApi inventory;private final MemberApi members;private final Commands commands;private final Outbox outbox;
    public AftersaleService(AftersaleMapper mapper,OrderApi orders,FulfillmentApi fulfillment,RefundApi refunds,InventoryApi inventory,MemberApi members,Commands commands,Outbox outbox,com.lrj.commerce.member.api.PointsSpendApi points){this.points=points;this.mapper=mapper;this.orders=orders;this.fulfillment=fulfillment;this.refunds=refunds;this.inventory=inventory;this.members=members;this.commands=commands;this.outbox=outbox;}
    /** 请求不含金额，退款只由订单固化快照和累计退货数量决定。 */
    public View request(Actor actor,String key,Request input){
        Inputs.require(input!=null&&input.items()!=null&&!input.items().isEmpty()&&input.items().size()<=100,"售后商品必须为1至100行");Identifiers.require(input.orderId());Inputs.text(input.reason(),512);
        var quantities=new TreeMap<String,Integer>();for(var item:input.items()){Inputs.require(item!=null&&item.quantity()>0&&item.quantity()<=10000,"退货数量无效");Identifiers.require(item.skuId());Inputs.require(quantities.put(item.skuId(),item.quantity())==null,"退货SKU不可重复");}
        return commands.run(actor,"aftersale.request",key,input,View.class,()->{
            var order=orders.read(actor,input.orderId());boolean returns=fulfillment.holdForAftersale(actor.tenantId(),order.orderId());
            var previous=new HashMap<String,Integer>();for(var r:mapper.returned(actor.tenantId(),order.orderId()))previous.merge(r.skuId(),r.quantity(),Integer::sum);
            List<Line> lines=new ArrayList<>();long total=0;boolean full=quantities.size()==order.items().size();
            for(var item:order.items()){
                int quantity=quantities.getOrDefault(item.skuId(),0);full&=quantity==item.quantity();if(quantity==0)continue;
                int before=previous.getOrDefault(item.skuId(),0);Inputs.require(before+quantity<=item.quantity(),"累计退货数量超出原订单");
                // 用整数分的累计差额分摊；最后一件承担余分，任意多次部分退款总和守恒。
                BigInteger cents=BigInteger.valueOf(new Money(new BigDecimal(item.payable())).minorUnits());BigInteger count=BigInteger.valueOf(item.quantity());
                long refund=cents.multiply(BigInteger.valueOf(before+quantity)).divide(count).subtract(cents.multiply(BigInteger.valueOf(before)).divide(count)).longValueExact();
                long returnPoints=BigInteger.valueOf(item.points()).multiply(BigInteger.valueOf(before+quantity)).divide(count).subtract(BigInteger.valueOf(item.points()).multiply(BigInteger.valueOf(before)).divide(count)).longValueExact();
                total=Math.addExact(total,refund);lines.add(new Line(item.skuId(),quantity,Money.minor(refund).amount().toPlainString(),returnPoints));
            }
            Inputs.require(lines.size()==quantities.size(),"退货商品不属于原订单");Inputs.require(returns||full,"未发货只支持整单退款");
            var view=new View(UUID.randomUUID().toString(),order.orderId(),order.memberId(),"REQUESTED",returns,Money.minor(total).amount().toPlainString(),null,0,List.copyOf(lines));
            mapper.insert(actor.tenantId(),view,input.reason(),JsonCodec.write(lines));for(var line:lines)mapper.line(actor.tenantId(),view.caseId(),line);return view;
        });
    }
    public View read(Actor actor,String id){Identifiers.require(id);var row=Inputs.found(mapper.find(actor.tenantId(),id));orders.read(actor,row.orderId());return view(row);}
    public List<View> list(Actor actor,String after,int limit){Inputs.page(after,limit);return mapper.list(actor.tenantId(),members.current(actor).memberId(),after,limit).stream().map(this::view).toList();}
    public List<View> adminList(Actor actor,String after,int limit){actor.requireAdmin();Inputs.page(after,limit);return mapper.list(actor.tenantId(),null,after,limit).stream().map(this::view).toList();}
    /** 批准未发货退款直接释放可售；已发货必须等收回实物。 */
    public View approve(Actor actor,String key,String id){actor.requireAdmin();return commands.run(actor,"aftersale.approve",key,id,View.class,()->{
        var row=Inputs.found(mapper.lock(actor.tenantId(),id));requireState(row,"REQUESTED");
        if(row.returnRequired())change(actor.tenantId(),row,"WAIT_RETURN",null);else startRefund(actor.tenantId(),row);return view(mapper.find(actor.tenantId(),id));
    });}
    public View reject(Actor actor,String key,String id){actor.requireAdmin();return commands.run(actor,"aftersale.reject",key,id,View.class,()->{var row=Inputs.found(mapper.lock(actor.tenantId(),id));requireState(row,"REQUESTED");change(actor.tenantId(),row,"REJECTED",null);fulfillment.finishAftersale(actor.tenantId(),row.orderId(),false);return view(mapper.find(actor.tenantId(),id));});}
    /** 收货事实和库存回补同事务，退款未知不妨碍记录已经收回的实物。 */
    public View receiveReturn(Actor actor,String key,String id){actor.requireAdmin();return commands.run(actor,"aftersale.receive",key,id,View.class,()->{var row=Inputs.found(mapper.lock(actor.tenantId(),id));requireState(row,"WAIT_RETURN");startRefund(actor.tenantId(),row);return view(mapper.find(actor.tenantId(),id));});}
    private void startRefund(String tenant,AftersaleMapper.Row row){for(var line:view(row).items().stream().sorted(Comparator.comparing(Line::skuId)).toList())inventory.returnItems(tenant,row.orderId(),row.caseId(),line.skuId(),line.quantity());var refund=refunds.request(tenant,row.caseId(),row.orderId(),row.refundAmount());change(tenant,row,"REFUNDING",refund.refundId());}
    public String consumer(){return "aftersale-refund-v1";}
    public Set<String> types(){return Set.of("refund.succeeded.v1");}
    /** 消费时再次验证支付域资金事实，完成后发布权益补偿事实。 */
    public void handle(Event event){
        var refund=refunds.internalRead(event.tenantId(),event.aggregateId());var row=Inputs.found(mapper.lock(event.tenantId(),refund.caseId()));if(row.status().equals("COMPLETED"))return;
        requireState(row,"REFUNDING");if(!refund.status().equals("SUCCEEDED")||!refund.orderId().equals(row.orderId())||!refund.refundId().equals(row.refundId())||new BigDecimal(refund.amount()).compareTo(new BigDecimal(row.refundAmount()))!=0)throw conflict();
        points.refund(event.tenantId(),row.orderId(),row.memberId(),row.caseId(),view(row).items().stream().mapToLong(Line::points).sum());
        change(event.tenantId(),row,"COMPLETED",refund.refundId());fulfillment.finishAftersale(event.tenantId(),row.orderId(),true);
        int returned=mapper.returned(event.tenantId(),row.orderId()).stream().mapToInt(AftersaleMapper.Returned::quantity).sum();int original=orders.internalRead(event.tenantId(),row.orderId()).items().stream().mapToInt(l->l.quantity()).sum();
        outbox.append(event.tenantId(),"aftersales.completed.v1",row.caseId(),row.version()+1,new Completion(row.caseId(),row.orderId(),refund.refundId(),returned==original));
    }
    private void requireState(AftersaleMapper.Row row,String state){if(!row.status().equals(state))throw conflict();}
    private void change(String tenant,AftersaleMapper.Row row,String status,String refundId){if(mapper.change(tenant,row.caseId(),row.version(),status,refundId)!=1)throw conflict();}
    private View view(AftersaleMapper.Row row){return new View(row.caseId(),row.orderId(),row.memberId(),row.status(),row.returnRequired(),row.refundAmount(),row.refundId(),row.version(),List.of(JsonCodec.read(row.itemsJson(),Line[].class)));}
    private DomainException conflict(){return new DomainException(DomainException.Code.CONFLICT,"售后当前状态或退款事实冲突");}
    /** 使用当前锁定读，迟到的付款事件不能基于旧快照启动退款后的营销。 */
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean fullyReturned(String tenant,String order){int returned=mapper.returned(tenant,order).stream().mapToInt(AftersaleMapper.Returned::quantity).sum();int original=orders.internalRead(tenant,order).items().stream().mapToInt(l->l.quantity()).sum();return returned>=original;}
}
