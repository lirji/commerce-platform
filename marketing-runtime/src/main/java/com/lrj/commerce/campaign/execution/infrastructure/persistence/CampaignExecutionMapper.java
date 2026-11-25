package com.lrj.commerce.campaign.execution.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 营销参与审计表唯一写入口；业务状态以数据库条件更新收敛。 */
@Mapper
public interface CampaignExecutionMapper {

	record Row(String orderId, String campaignId, long campaignVersion, String quoteId, String memberId,
			String storeId, String ruleId, Long ruleVersion, String audienceId, Long audienceVersion,
			String benefitId, Long benefitVersion, String grantId, String discountAmount, String reasonCode,
			String status, Instant evaluatedAt, Instant createdAt, Instant updatedAt, long lockVersion,
			String benefitType, String eventId, String eventStatus, String failureClass) {
	}

	void insert(@Param("tenant") String tenant, @Param("row") Row row);

	Row find(@Param("tenant") String tenant, @Param("order") String order, @Param("campaign") String campaign);

	List<Row> byOrder(@Param("tenant") String tenant, @Param("order") String order);

	/** 主键查询与允许的字面关键词在游标前共同限制本租户读取。 */
	List<Row> list(@Param("tenant") String tenant, @Param("after") String after, @Param("limit") int limit,
			@Param("filter") com.lrj.commerce.runtime.api.validation.ListFilter filter,
			@Param("eventType") String eventType, @Param("failureClass") String failureClass);

	int paid(@Param("tenant") String tenant, @Param("order") String order, @Param("campaign") String campaign);

	int released(@Param("tenant") String tenant, @Param("order") String order, @Param("campaign") String campaign);

	int granted(@Param("tenant") String tenant, @Param("order") String order, @Param("grant") String grant);

}
