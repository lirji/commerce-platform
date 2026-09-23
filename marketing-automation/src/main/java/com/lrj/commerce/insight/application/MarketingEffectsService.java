package com.lrj.commerce.insight.application;
import com.lrj.commerce.insight.api.MarketingEffectsApi;
import com.lrj.commerce.insight.infrastructure.EffectsMapper;
import com.lrj.commerce.ordering.api.OrderApi;
import com.lrj.commerce.trade.api.QuoteApi;
import com.lrj.commerce.payment.api.RefundApi;
import com.lrj.commerce.store.api.StoreApi;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import java.util.*;
import java.time.*;

/** 按订单键重建读模型，成交和成功退款仅单调前进，重放不叠加金额。 */
@Service
public class MarketingEffectsService implements MarketingEffectsApi,EventHandler {
 private final Clock clock;private final EffectsMapper mapper;private final OrderApi orders;private final QuoteApi quotes;private final RefundApi refunds;private final StoreApi stores;private final Commands commands;
 public MarketingEffectsService(EffectsMapper mapper,OrderApi orders,QuoteApi quotes,RefundApi refunds,StoreApi stores,Commands commands,Clock clock){this.clock=clock;this.mapper=mapper;this.orders=orders;this.quotes=quotes;this.refunds=refunds;this.stores=stores;this.commands=commands;}
 /** 有界UTC窗口由Controller生成，接口仍防止内部调用无界扫描。 */
 public List<Daily> daily(Actor actor,String store,Instant from,Instant to){check(actor,store,from,to,"",100);return mapper.daily(actor.tenantId(),store,from,to);}
 public String consumer(){return "marketing-effects-v1";}
 public Set<String> types(){return Set.of("order.created.v1","order.paid.v1","order.ready.v1","order.completed.v1","order.cancelled.v1","refund.succeeded.v1");}
 /** 事件只是核对信号，实际金额均通过权威领域API重新读取。 */
 public void handle(Event event){String id=event.aggregateId();if(event.eventType().equals("refund.succeeded.v1")){var signal=JsonCodec.read(event.payloadJson(),RefundApi.View.class);id=refunds.internalRead(event.tenantId(),signal.refundId()).orderId();}project(event.tenantId(),List.of(orders.internalRead(event.tenantId(),id)));}
 /** 时间窗口限制扫描，列表结果使用稳定聚合游标。 */
 public Report report(Actor actor,String store,Instant from,Instant to,String after,int limit){
  actor.requireAdmin();stores.requireActive(actor,store);Inputs.page(after,limit);Inputs.require(from!=null&&to!=null&&to.isAfter(from)&&Duration.between(from,to).compareTo(Duration.ofDays(93))<=0,"分析窗口需为93天内");
  return new Report(mapper.report(actor.tenantId(),store,from,to,after,limit),"按下单UTC时间选择订单，扣除截至最后投影时已知成功退款（含窗口外退款）", "仅覆盖已消费事件或已重建的订单；历史订单可分批补齐，事件积压会产生延迟", "优惠承担为成交快照，退款不自动恢复预算；不含货品、支付及渠道成本，不等于利润或因果ROI");
 }
 /** 入组后的成交只是关联结果；同订单可出现在不同版本，明确拒绝伪造因果ROI。 */
 public JourneyReport journeys(Actor actor,String store,Instant from,Instant to,String after,int limit){check(actor,store,from,to,after,limit);return new JourneyReport(mapper.journeys(actor.tenantId(),store,from,to,after,limit,clock.instant()),"按入组UTC时间选择队列；每会员同版本首次入组为锚点，统计观察窗内下单且已付订单；跨行不可相加，不代表因果提升", "仅已消费/重建且具有会员归属的订单；完整观察会员仍可能受事件延迟影响，成功退款包含窗口外退款", "优惠承担来自成交快照，不含权益货品、支付或渠道成本，不等于利润或ROI");}
 /** 批次券按钱包ID关联，订单其他优惠不混入券成本。 */
 public DeliveryReport deliveries(Actor actor,String store,Instant from,Instant to,String after,int limit){check(actor,store,from,to,after,limit);return new DeliveryReport(mapper.deliveries(actor.tenantId(),store,from,to,after,limit),"按批次创建UTC时间选择；仅实际使用该批次券的已付订单，扣除全部已知成功退款", "新批次与已消费/重建的订单可关联；升级前创建时间未知的批次不计入时间窗，事件积压可能延迟", "券优惠为成交时该券自身优惠，退款不自动恢复补贴；不含其他活动、积分或货品渠道成本");}
 private void check(Actor actor,String store,Instant from,Instant to,String after,int limit){actor.requireAdmin();stores.requireActive(actor,store);Inputs.page(after,limit);Inputs.require(from!=null&&to!=null&&to.isAfter(from)&&Duration.between(from,to).compareTo(Duration.ofDays(93))<=0,"分析窗口需为93天内");}
 /** 可重复补齐历史，断开后从返回游标继续，或从头安全重跑。 */
 public RebuildResult rebuild(Actor actor,String key,Rebuild input){
  actor.requireAdmin();Inputs.require(input!=null,"重建参数缺失");Inputs.page(input.after(),input.limit());
  return commands.run(actor,"marketing.effects.rebuild",key,input,RebuildResult.class,()->{var batch=orders.adminList(actor,input.after(),input.limit());if(!batch.isEmpty())project(actor.tenantId(),batch);return new RebuildResult(batch.size(),batch.isEmpty()?input.after():batch.getLast().orderId(),batch.size()==input.limit());});
 }
 private void project(String tenant,List<OrderApi.View> batch){
  // 报价和退款汇总各一次有界批量读取，避免按订单调用数据库形成N+1。
  var snapshots=new HashMap<String,QuoteApi.View>();quotes.internalBatch(tenant,batch.stream().map(OrderApi.View::quoteId).toList()).forEach(q->snapshots.put(q.quoteId(),q));
  var returned=new HashMap<String,String>();refunds.totals(tenant,batch.stream().map(OrderApi.View::orderId).toList()).forEach(r->returned.put(r.orderId(),r.amount()));
  for(var order:batch){var quote=Inputs.found(snapshots.get(order.quoteId()));Inputs.require(quote.memberId().equals(order.memberId())&&quote.storeId().equals(order.storeId())&&quote.payable().equals(order.payable()),"成交快照归属或金额不一致");
   String campaign=quote.campaign()==null?null:quote.campaign().campaignId();Long version=quote.campaign()==null?null:quote.campaign().version();
   String platform=quote.funding()==null?"0.00":quote.funding().platformFunding(),merchant=quote.funding()==null?quote.discount():quote.funding().merchantFunding();
   mapper.observe(tenant,new EffectsMapper.Fact(order.orderId(),order.storeId(),JsonCodec.hash(JsonCodec.write(Arrays.asList(order.storeId(),campaign,version))),campaign,version,order.createdAt(),Set.of("PAID","FULFILLING","COMPLETED").contains(order.status()),order.payable(),returned.getOrDefault(order.orderId(),"0.00"),quote.discount(),platform,merchant,order.memberId(),quote.coupon()==null?null:quote.coupon().couponId(),quote.coupon()==null?"0.00":quote.coupon().discount()));
  }
 }
}
