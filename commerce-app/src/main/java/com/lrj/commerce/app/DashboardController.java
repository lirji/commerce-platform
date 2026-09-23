package com.lrj.commerce.app;
import com.lrj.commerce.catalog.api.CatalogApi;
import com.lrj.commerce.member.api.MemberApi;
import com.lrj.commerce.insight.api.MarketingEffectsApi;
import com.lrj.commerce.runtime.api.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
/** 总览只组装各域聚合，不越过API读取其他域业务表。 */
@RestController @RequestMapping("/v1/admin")
public class DashboardController {
 public record Totals(long paidOrders,String received,String refunded,String netReceipts,String discountGranted) { }
 public record View(String storeId,Instant from,Instant to,Instant generatedAt,MemberApi.Stats members,CatalogApi.Stats catalog,List<MarketingEffectsApi.Daily> daily,Totals totals,String coverage) { }
 private final MemberApi members;private final CatalogApi catalog;private final MarketingEffectsApi effects;private final Clock clock;
 public DashboardController(MemberApi members,CatalogApi catalog,MarketingEffectsApi effects,Clock clock){this.members=members;this.catalog=catalog;this.effects=effects;this.clock=clock;}
 /** 今天及前29个UTC日，金额精确累加后输出，前端仅将数值用于图形高度。 */
 @GetMapping("/dashboard") public View read(@AuthenticationPrincipal Actor actor,@RequestParam String storeId){actor.requireAdmin();Instant now=clock.instant();LocalDate today=now.atZone(ZoneOffset.UTC).toLocalDate(),start=today.minusDays(29);Instant from=start.atStartOfDay(ZoneOffset.UTC).toInstant();var stats=catalog.stats(actor,storeId);var source=effects.daily(actor,storeId,from,now);var byDay=new HashMap<LocalDate,MarketingEffectsApi.Daily>();source.forEach(d->byDay.put(d.day(),d));var days=new ArrayList<MarketingEffectsApi.Daily>();long paid=0;BigDecimal received=BigDecimal.ZERO,refunded=BigDecimal.ZERO,net=BigDecimal.ZERO,discount=BigDecimal.ZERO;
  for(LocalDate day=start;!day.isAfter(today);day=day.plusDays(1)){var value=byDay.getOrDefault(day,new MarketingEffectsApi.Daily(day,0,0,"0.00","0.00","0.00","0.00","0.00","0.00",null));days.add(value);paid=Math.addExact(paid,value.paidOrders());received=received.add(new BigDecimal(value.received()));refunded=refunded.add(new BigDecimal(value.refunded()));net=net.add(new BigDecimal(value.netReceipts()));discount=discount.add(new BigDecimal(value.discountGranted()));}
  return new View(storeId,from,now,now,members.stats(actor),stats,List.copyOf(days),new Totals(paid,amount(received),amount(refunded),amount(net),amount(discount)),"按UTC下单日期选择订单，扣除截至投影更新时已知的成功退款（含窗口外退款）。仅覆盖已消费事件或重建的订单，存在异步延迟；各域读取不构成同一数据库快照。");
 }
 private String amount(BigDecimal value){return value.setScale(2).toPlainString();}
}
