package com.lrj.commerce.campaign.infrastructure;
import com.lrj.commerce.campaign.api.CampaignFundingApi.*;
import org.apache.ibatis.annotations.*;
import java.util.List;
/** 活动版本预算条件更新与订单预占台账。 */
@Mapper
public interface BudgetMapper {
    void create(@Param("tenant") String tenant,@Param("id") String id,@Param("campaign") String campaign,@Param("version") long version,@Param("cap") String cap);
    int reserve(@Param("tenant") String tenant,@Param("input") Commitment input);
    void hold(@Param("tenant") String tenant,@Param("order") String order,@Param("input") Commitment input);
    record Hold(String campaignId,long version,String discount,String status) { }
    Hold lock(@Param("tenant") String tenant,@Param("order") String order);
    int finishBudget(@Param("tenant") String tenant,@Param("hold") Hold hold,@Param("confirmed") boolean confirmed);
    int finishHold(@Param("tenant") String tenant,@Param("order") String order,@Param("status") String status);
    List<Budget> list(@Param("tenant") String tenant,@Param("after") String after,@Param("limit") int limit);
}
