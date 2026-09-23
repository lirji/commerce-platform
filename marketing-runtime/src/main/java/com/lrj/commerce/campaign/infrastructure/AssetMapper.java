package com.lrj.commerce.campaign.infrastructure;
import com.lrj.commerce.campaign.api.MarketingAssets.*;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;
/** 批量查询快照资格，避免活动候选逐条访问数据库。 */
@Mapper
public interface AssetMapper {
    void audience(@Param("tenant") String tenant,@Param("input") Audience input,@Param("count") int count);
    void members(@Param("tenant") String tenant,@Param("input") Audience input);
    List<String> audienceMembers(@Param("tenant") String tenant,@Param("ref") Ref ref,@Param("after") String after,@Param("limit") int limit);
    AudienceView audienceFind(@Param("tenant") String tenant,@Param("id") String id,@Param("version") long version);
    List<AudienceView> audiences(@Param("tenant") String tenant,@Param("after") String after,@Param("limit") int limit);
    record Membership(String audienceId,long version,String source,Instant watermark,Instant validUntil,boolean matched) { }
    List<Membership> sources(@Param("tenant") String tenant,@Param("member") String member,@Param("refs") List<Ref> refs);
    record RuleRow(String ruleId,long version,String name,String ruleJson,String status) { }
    void rule(@Param("tenant") String tenant,@Param("input") Rule input,@Param("json") String json);
    RuleRow ruleFind(@Param("tenant") String tenant,@Param("id") String id,@Param("version") long version);
    int publishRule(@Param("tenant") String tenant,@Param("id") String id,@Param("version") long version);
    List<RuleRow> rules(@Param("tenant") String tenant,@Param("after") String after,@Param("limit") int limit);
}
