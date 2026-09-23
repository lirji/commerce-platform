package com.lrj.commerce.fulfillment.application;
import com.lrj.commerce.fulfillment.api.*;
import com.lrj.commerce.fulfillment.infrastructure.FulfillmentMapper;
import com.lrj.commerce.ordering.api.OrderApi;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
/** 履约与订单生命周期经端口同事务协作，远程证明在命令事务前取得。 */
@Service
public class FulfillmentService implements FulfillmentApi,EventHandler {
    private final FulfillmentMapper mapper;private final OrderApi orders;private final Commands commands;private final WmsPort wms;
    public FulfillmentService(FulfillmentMapper mapper,OrderApi orders,Commands commands,WmsPort wms){this.mapper=mapper;this.orders=orders;this.commands=commands;this.wms=wms;}
    public View read(Actor actor,String order){orders.read(actor,order);return Inputs.found(mapper.find(actor.tenantId(),order));}
    public List<View> list(Actor actor,String after,int limit){actor.requireAdmin();Inputs.page(after,limit);return mapper.list(actor.tenantId(),after,limit);}
    /** 发货与售后阻拦争同一履约锁；单个包裹只允许一个不可覆盖运单号。 */
    public View ship(Actor actor,String key,String order,Ship input){
        actor.requireAdmin();Identifiers.require(order);Inputs.require(input!=null,"缺少发货信息");Inputs.text(input.trackingNo(),64);
        orders.internalRead(actor.tenantId(),order);var proof=wms.shipment(actor.tenantId(),order,input.trackingNo());
        return commands.run(actor,"fulfillment.ship",key,Map.of("order",order,"input",input),View.class,()->{
            ensurePaid(actor.tenantId(),order);var current=mapper.lock(actor.tenantId(),order);
            if(Set.of("SHIPPED","DELIVERED").contains(current.status())){if(!current.trackingNo().equals(proof.trackingNo()))throw conflict();return current;}
            if(mapper.ship(actor.tenantId(),order,proof.trackingNo(),proof.provider(),current.version())!=1)throw conflict();
            orders.fulfillmentFact(actor.tenantId(),order,false);return mapper.find(actor.tenantId(),order);
        });
    }
    /** 已送达再次证明不重复推进订单。 */
    public View deliver(Actor actor,String key,String order){
        actor.requireAdmin();Identifiers.require(order);var row=Inputs.found(mapper.find(actor.tenantId(),order));wms.delivered(actor.tenantId(),order,row.trackingNo());
        return commands.run(actor,"fulfillment.deliver",key,order,View.class,()->{
            var current=Inputs.found(mapper.lock(actor.tenantId(),order));if(current.status().equals("DELIVERED"))return current;
            if(mapper.deliver(actor.tenantId(),order,current.version())!=1)throw conflict();orders.fulfillmentFact(actor.tenantId(),order,true);return mapper.find(actor.tenantId(),order);
        });
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean holdForAftersale(String tenant,String order){ensurePaid(tenant,order);var current=mapper.lock(tenant,order);if(current.blocked()||current.status().equals("CANCELLED"))throw conflict();if(mapper.block(tenant,order,true,current.status(),current.version())!=1)throw conflict();return !current.status().equals("READY");}
    @Transactional(propagation=Propagation.MANDATORY)
    public void finishAftersale(String tenant,String order,boolean refunded){var current=Inputs.found(mapper.lock(tenant,order));if(!current.blocked())throw conflict();String status=refunded&&current.status().equals("READY")?"CANCELLED":current.status();if(mapper.block(tenant,order,false,status,current.version())!=1)throw conflict();}
    public String consumer(){return "fulfillment-order-v1";}
    public Set<String> types(){return Set.of("order.paid.v1","order.ready.v1");}
    /** 根据权威订单复核，迟到重复事件不能重建已取消履约。 */
    public void handle(Event event){ensurePaid(event.tenantId(),event.aggregateId());}
    private void ensurePaid(String tenant,String order){var view=orders.internalRead(tenant,order);if(!Set.of("PAID","FULFILLING","COMPLETED").contains(view.status()))throw conflict();mapper.ensure(tenant,order);}
    private DomainException conflict(){return new DomainException(DomainException.Code.CONFLICT,"履约当前状态不允许操作");}
}
