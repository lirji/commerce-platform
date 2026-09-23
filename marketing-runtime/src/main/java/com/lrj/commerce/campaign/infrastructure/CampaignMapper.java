package com.lrj.commerce.campaign.infrastructure;
import com.lrj.commerce.campaign.api.CampaignApi.Draft;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

/** 活动表唯一写入口，不向其他模块暴露持久化行。 */
@Mapper
public interface CampaignMapper {
    record Row(String campaignId,long version,String storeId,String merchantId,String name,Instant validFrom,Instant validTo,String minimumSpend,String discountAmount,String ruleJson,String status,long lockVersion) { }
    void insert(@Param("tenant") String tenant,@Param("merchant") String merchant,@Param("input") Draft input,@Param("rule") String rule);
    Row find(@Param("tenant") String tenant,@Param("id") String id,@Param("version") long version);
    List<Row> lockGroup(@Param("tenant") String tenant,@Param("id") String id);
    void pauseOthers(@Param("tenant") String tenant,@Param("id") String id);
    int change(@Param("tenant") String tenant,@Param("id") String id,@Param("version") long version,@Param("expected") long expected,@Param("status") String status);
    List<Row> list(@Param("tenant") String tenant,@Param("after") String after,@Param("limit") int limit);
    List<Row> published(@Param("tenant") String tenant,@Param("store") String store);
}
