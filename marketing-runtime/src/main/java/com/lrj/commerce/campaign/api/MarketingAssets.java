package com.lrj.commerce.campaign.api;
import com.lrj.commerce.runtime.api.Actor;
import java.time.Instant;
import java.util.List;
/** 版本化规则与人群资产，不将客户端事实注入交易决策。 */
public interface MarketingAssets {
    java.util.Map<String,String> TRUSTED_FIELDS=java.util.Map.ofEntries(
        java.util.Map.entry("memberLevel","TEXT"),java.util.Map.entry("orderAmount","DECIMAL"),java.util.Map.entry("memberGrowth","DECIMAL"),java.util.Map.entry("memberNetSpend","DECIMAL"),java.util.Map.entry("memberStatus","TEXT"),java.util.Map.entry("memberTags","TEXT"),
        java.util.Map.entry("memberBrowse30","DECIMAL"),java.util.Map.entry("memberCart30","DECIMAL"),java.util.Map.entry("memberOrders30","DECIMAL"),java.util.Map.entry("memberSpend30","DECIMAL"),java.util.Map.entry("memberDaysSinceOrder","DECIMAL"),java.util.Map.entry("memberDaysSinceJoin","DECIMAL"),java.util.Map.entry("memberBirthdayToday","TEXT"),java.util.Map.entry("memberJourneyEnabled","TEXT"));
    record Ref(String id,long version) { }
    record Audience(String audienceId,long version,String name,String source,Instant watermark,Instant validUntil,List<String> memberIds) { }
    record AudienceView(String audienceId,long version,String name,String source,Instant watermark,Instant validUntil,int memberCount) { }
    record Source(String audienceId,long version,String source,Instant watermark,Instant validUntil,String match) { }
    record Rule(String ruleId,long version,String name,RuleNode rule) { }
    record RuleView(Rule content,String status) { }
    AudienceView createAudience(Actor actor,String key,Audience input);
    List<AudienceView> audiences(Actor actor,String after,int limit);
    List<Source> sources(String tenant,String member,List<Ref> refs,Instant now);
    /** 已发布固定人群的有界成员游标，不能读取未完成刷新结果。 */
    List<String> members(String tenant,Ref ref,String after,int limit);
    void requireFresh(String tenant,Ref ref,Instant now);
    RuleView createRule(Actor actor,String key,Rule input);
    RuleView publishRule(Actor actor,String key,String id,long version);
    List<RuleView> rules(Actor actor,String after,int limit);
    RuleNode publishedRule(String tenant,Ref ref);
}
