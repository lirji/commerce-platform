package com.lrj.commerce.campaign.api;
import com.lrj.commerce.member.api.MemberGrowthApi;
import com.lrj.commerce.marketing.api.Fact;
import java.util.*;
import java.math.BigDecimal;

/** 活动、旅程、人群共享明确的可信事实映射，订单事实只由调用用例补入。 */
public final class MemberRuleFacts {
 private MemberRuleFacts(){ }
 public static Map<String,Fact> from(MemberGrowthApi.Facts member,String orderAmount){
  Map<String,Fact> facts=new HashMap<>();facts.put("memberLevel",new Fact.Text(member.memberLevel()));facts.put("memberStatus",new Fact.Text(member.status()));facts.put("memberGrowth",new Fact.Decimal(BigDecimal.valueOf(member.growth())));facts.put("memberNetSpend",new Fact.Decimal(new BigDecimal(member.netSpend())));facts.put("memberTags",new Fact.Tags(new HashSet<>(member.tags())));
  if(orderAmount!=null)facts.put("orderAmount",new Fact.Decimal(new BigDecimal(orderAmount)));return Map.copyOf(facts);
 }
}
