package com.lrj.commerce.payment.application;
import com.lrj.commerce.payment.api.*;
import com.lrj.commerce.payment.infrastructure.PaymentMapper;
import com.lrj.commerce.ordering.api.OrderApi;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.*;
/** 支付意图、渠道观察和资金事实分开提交；任何超时都保留可重查的尝试。 */
@Service
public class PaymentService implements PaymentApi,EventHandler {
    private final PaymentMapper mapper;private final OrderApi orders;private final Commands commands;private final PaymentChannel channel;private final Outbox outbox;private final TransactionTemplate tx;
    public PaymentService(PaymentMapper mapper,OrderApi orders,Commands commands,PaymentChannel channel,Outbox outbox,PlatformTransactionManager manager){this.mapper=mapper;this.orders=orders;this.commands=commands;this.channel=channel;this.outbox=outbox;tx=new TransactionTemplate(manager);tx.setTimeout(10);}
    /** 先持久化唯一意图，再调用渠道；远程失败不回滚已发起支付的订单。 */
    public View start(Actor actor,String key,String orderId){
        Identifiers.require(orderId);String provider=channel.provider();
        var result=commands.run(actor,"payment.start",key,orderId,View.class,()->{
            var order=orders.beginPayment(actor,orderId);var existing=mapper.byOrder(actor.tenantId(),orderId);if(existing!=null)return existing;
            var view=new View(UUID.randomUUID().toString(),orderId,order.payable(),"CNY",provider,Status.UNKNOWN,0);mapper.insert(actor.tenantId(),view);return view;
        });channel.ensure(actor.tenantId(),result);return result;
    }
    /** 先校验订单归属，避免用支付ID越权查询。 */
    public View read(Actor actor,String orderId){orders.read(actor,orderId);return Inputs.found(mapper.byOrder(actor.tenantId(),orderId));}
    /** 观察渠道在事务外，终态在行锁内复核；迟到OPEN不能覆盖PAID。 */
    public View reconcile(Actor actor,String orderId){
        orders.read(actor,orderId);return reconcileInternal(actor.tenantId(),orderId);
    }
    private View reconcileInternal(String tenant,String orderId){
        var order=orders.internalRead(tenant,orderId);var attempt=Inputs.found(mapper.byOrder(tenant,orderId));
        channel.ensure(tenant,attempt);
        var proof=order.status().equals("CLOSING")?channel.close(tenant,attempt.paymentId()):channel.observe(tenant,attempt.paymentId());
        return tx.execute(s->{
            var current=Inputs.found(mapper.lock(tenant,attempt.paymentId()));
            if(!proof.tenantId().equals(tenant)||!proof.orderId().equals(orderId)||!proof.paymentId().equals(current.paymentId())||!proof.currency().equals(current.currency())||new BigDecimal(proof.amount()).compareTo(new BigDecimal(current.amount()))!=0)throw new DomainException(DomainException.Code.CONFLICT,"渠道证据与支付尝试不匹配");
            if(current.status()==Status.PAID||current.status()==Status.CLOSED){if((proof.status()==Status.PAID||proof.status()==Status.CLOSED)&&proof.status()!=current.status())throw new DomainException(DomainException.Code.CONFLICT,"渠道终态证据冲突");return current;}
            if(proof.status()==Status.UNKNOWN||proof.status()==current.status())return current;
            if(proof.status()==Status.PAID)Inputs.text(proof.transactionId(),64);
            if(mapper.change(tenant,current.paymentId(),current.version(),proof.status().name(),proof.transactionId(),JsonCodec.write(proof))!=1)throw new DomainException(DomainException.Code.CONFLICT,"支付版本冲突");
            var updated=mapper.find(tenant,current.paymentId());
            if(updated.status()==Status.PAID||updated.status()==Status.CLOSED)outbox.append(tenant,updated.status()==Status.PAID?"payment.paid.v1":"payment.closed.v1",updated.paymentId(),updated.version(),updated);
            return updated;
        });
    }
    /** 此端点只改变沙箱账本；应用收款还必须通过reconcile核对。 */
    public View sandboxFact(Actor actor,String key,String id,SandboxFact fact){
        actor.requireAdmin();Identifiers.require(id);Inputs.require(fact!=null&&(fact.status()==Status.OPEN||fact.status()==Status.PAID),"沙箱只允许待付或成功事实");
        Inputs.require(channel.provider().equals("SANDBOX"),"当前渠道不是沙箱");
        return commands.run(actor,"sandbox.payment.fact",key,Map.of("id",id,"fact",fact),View.class,()->{
            var attempt=Inputs.found(mapper.find(actor.tenantId(),id));var existing=Inputs.found(mapper.channel(actor.tenantId(),id));
            if(existing.status()==Status.PAID||existing.status()==Status.CLOSED){if(existing.status()!=fact.status())throw new DomainException(DomainException.Code.CONFLICT,"沙箱渠道终态不可覆盖");return attempt;}
            if(mapper.channelFact(actor.tenantId(),id,fact.status().name(),fact.status()==Status.PAID?UUID.randomUUID().toString():null)!=1)throw new DomainException(DomainException.Code.CONFLICT,"沙箱状态发生变化");return attempt;
        });
    }
    public String consumer(){return "order-payment-v1";}
    public Set<String> types(){return Set.of("payment.paid.v1","payment.closed.v1");}
    /** Inbox已在调度器事务中，支付事实和订单库存更新要么一起提交要么一起回滚。 */
    public void handle(Event event){
        var fact=JsonCodec.read(event.payloadJson(),View.class);var current=Inputs.found(mapper.find(event.tenantId(),event.aggregateId()));
        if(!current.equals(fact))throw new DomainException(DomainException.Code.CONFLICT,"事件不是当前可信支付终态");
        boolean paid=event.eventType().equals("payment.paid.v1");if(current.status()!=(paid?Status.PAID:Status.CLOSED))throw new DomainException(DomainException.Code.CONFLICT,"支付事件类型不匹配");
        orders.paymentFact(event.tenantId(),fact.orderId(),fact.amount(),paid);
    }
    private String checkCursor="";
    /** 条件领取先提交，渠道调用不占事务；崩溃后到期重查相同幂等请求。 */
    public synchronized int tick(){
        var tenants=mapper.dueTenants(checkCursor);if(tenants.isEmpty()){checkCursor="";return 0;}int count=0;
        for(String tenant:tenants)for(var check:mapper.due(tenant)){
            int delay=(1<<Math.min(check.checkAttempts()+2,6))+java.util.concurrent.ThreadLocalRandom.current().nextInt(2);
            if(mapper.claimCheck(tenant,check.paymentId(),check.checkAttempts(),delay)!=1)continue;
            try{reconcileInternal(tenant,check.orderId());count++;}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("payment check id={} errorType={}",check.paymentId(),failure.getClass().getSimpleName());}
        }checkCursor=tenants.getLast();return count;
    }
}
