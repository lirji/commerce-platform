package com.lrj.commerce.aftersales.infrastructure.persistence;

import com.lrj.commerce.aftersales.api.AftersaleApi;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 活动申请唯一键和行退货累计由售后域拥有。 */
@Mapper
public interface AftersaleMapper {

	record Row(String caseId, String orderId, String memberId, String status, boolean returnRequired,
			String refundAmount, String refundId, long version, String itemsJson) {
	}

	record Returned(String skuId, int quantity) {
	}

	void insert(@Param("tenant") String tenant, @Param("view") AftersaleApi.View view, @Param("reason") String reason,
			@Param("items") String items);

	void line(@Param("tenant") String tenant, @Param("caseId") String caseId, @Param("line") AftersaleApi.Line line);

	Row find(@Param("tenant") String tenant, @Param("id") String id);

	Row lock(@Param("tenant") String tenant, @Param("id") String id);

	List<Row> list(@Param("tenant") String tenant, @Param("member") String member, @Param("after") String after,
			@Param("limit") int limit, @org.apache.ibatis.annotations.Param("filter") com.lrj.commerce.runtime.api.validation.ListFilter filter);

	/** 兼容内部既有读取调用，缺省时不附加筛选条件。 */
	default List<Row> list(@Param("tenant") String tenant, @Param("member") String member, @Param("after") String after,
			@Param("limit") int limit) {
		return list(tenant, member, after, limit, com.lrj.commerce.runtime.api.validation.ListFilter.none());
	}

	List<Returned> returned(@Param("tenant") String tenant, @Param("order") String order);

	int change(@Param("tenant") String tenant, @Param("id") String id, @Param("version") long version,
			@Param("status") String status, @Param("refundId") String refundId);

	/** 只读订单归属投影，不写跨模块表；实际门店过滤先于分页。 */
	List<Row> scopedList(@Param("tenant") String tenant,
			@Param("scope") com.lrj.commerce.runtime.api.scope.ScopeQuery.Filter scope,
			@Param("after") String after, @Param("limit") int limit, @org.apache.ibatis.annotations.Param("filter") com.lrj.commerce.runtime.api.validation.ListFilter filter);

	/** 兼容内部既有读取调用，缺省时不附加筛选条件。 */
	default List<Row> scopedList(@Param("tenant") String tenant,
			@Param("scope") com.lrj.commerce.runtime.api.scope.ScopeQuery.Filter scope,
			@Param("after") String after, @Param("limit") int limit) {
		return scopedList(tenant, scope, after, limit, com.lrj.commerce.runtime.api.validation.ListFilter.none());
	}

}
