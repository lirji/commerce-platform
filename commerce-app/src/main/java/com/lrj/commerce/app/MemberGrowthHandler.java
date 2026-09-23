package com.lrj.commerce.app;
import com.lrj.commerce.member.api.MemberGrowthApi;
import com.lrj.commerce.ordering.api.OrderApi;
import com.lrj.commerce.payment.api.RefundApi;
import com.lrj.commerce.runtime.api.EventHandler;
import com.lrj.commerce.runtime.JsonCodec;
import org.springframework.stereotype.Component;
import java.util.Set;

/** 装配层连接交易事实与会员成长/积分，避免会员域反向依赖订单形成循环。 */
@Component
public class MemberGrowthHandler implements EventHandler {
 private final com.lrj.commerce.member.api.MemberPointsApi points;private final MemberGrowthApi growth;private final OrderApi orders;private final RefundApi refunds;
 public MemberGrowthHandler(MemberGrowthApi growth,OrderApi orders,RefundApi refunds,com.lrj.commerce.member.api.MemberPointsApi points){this.points=points;this.growth=growth;this.orders=orders;this.refunds=refunds;}
 public String consumer(){return "member-growth-v1";}
 public Set<String> types(){return Set.of("order.completed.v1","refund.succeeded.v1");}
 /** 只消费已持久化真实状态，不信任事件载荷自报金额。 */
 public void handle(Event event){
  RefundApi.View refund=null;String orderId;
  if(event.eventType().equals("refund.succeeded.v1")){var signal=JsonCodec.read(event.payloadJson(),RefundApi.View.class);refund=refunds.internalRead(event.tenantId(),signal.refundId());if(!refund.status().equals("SUCCEEDED"))throw new IllegalStateException("退款事实尚未成功");orderId=refund.orderId();}
  else orderId=event.aggregateId();
  var order=orders.internalRead(event.tenantId(),orderId);
  // 零元售后没有资金冲回，不伪造正金额退款账本。
  if(refund!=null&&new java.math.BigDecimal(refund.amount()).signum()==0)refund=null;
  var fact=new MemberGrowthApi.OrderFact(order.orderId(),order.memberId(),order.payable(),order.createdAt(),order.status().equals("COMPLETED"),refund==null?null:refund.refundId(),refund==null?null:refund.amount());
  growth.observe(event.tenantId(),fact);points.observe(event.tenantId(),fact);
 }
}
