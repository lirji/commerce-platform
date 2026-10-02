package com.lrj.commerce.fulfillment.infrastructure.persistence;

import com.lrj.commerce.fulfillment.api.FulfillmentApi.View;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 履约表只由本域写入；业务冲突通过条件更新暴露。 */
@Mapper
public interface FulfillmentMapper {

	void ensure(@Param("tenant") String tenant, @Param("order") String order);

	View find(@Param("tenant") String tenant, @Param("order") String order);

	View lock(@Param("tenant") String tenant, @Param("order") String order);

	List<View> list(@Param("tenant") String tenant, @Param("after") String after, @Param("limit") int limit);

	int ship(@Param("tenant") String tenant, @Param("order") String order, @Param("tracking") String tracking,
			@Param("provider") String provider, @Param("version") long version);

	int deliver(@Param("tenant") String tenant, @Param("order") String order, @Param("version") long version);

	int block(@Param("tenant") String tenant, @Param("order") String order, @Param("blocked") boolean blocked,
			@Param("status") String status, @Param("version") long version);

	/** 只读订单归属投影，不写跨模块表；实际门店过滤先于分页。 */
	List<View> scopedList(@Param("tenant") String tenant,
			@Param("scope") com.lrj.commerce.runtime.api.scope.ScopeQuery.Filter scope,
			@Param("after") String after, @Param("limit") int limit);

}
