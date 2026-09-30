package com.lrj.commerce.store.management.infrastructure.persistence;

import com.lrj.commerce.store.management.api.StoreApi;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 仅访问store权威表，所有谓词绑定可信tenant。 */
@Mapper
public interface StoreMapper {

	void insert(@Param("tenant") String tenant, @Param("input") StoreApi.Create input);

	StoreApi.View find(@Param("tenant") String tenant, @Param("id") String id);

	StoreApi.View lock(@Param("tenant") String tenant, @Param("id") String id);

	List<StoreApi.View> list(@Param("tenant") String tenant, @Param("after") String after, @Param("limit") int limit);

	/** 范围谓词与租户在分页前生效。 */
	List<StoreApi.View> listScoped(@Param("tenant") String tenant,
			@Param("scope") com.lrj.commerce.runtime.api.scope.ScopeQuery.Filter scope,
			@Param("after") String after, @Param("limit") int limit);

}
