package com.lrj.commerce.merchant.infrastructure.persistence;

import com.lrj.commerce.merchant.api.MerchantApi;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 仅访问merchant权威表，所有谓词绑定可信tenant。 */
@Mapper
public interface MerchantMapper {

	void insert(@Param("tenant") String tenant, @Param("input") MerchantApi.Create input);

	MerchantApi.View find(@Param("tenant") String tenant, @Param("id") String id);

	List<MerchantApi.View> list(@Param("tenant") String tenant, @Param("after") String after,
			@Param("limit") int limit);

	/** 范围谓词与租户在分页前生效。 */
	List<MerchantApi.View> listScoped(@Param("tenant") String tenant,
			@Param("scope") com.lrj.commerce.runtime.api.scope.ScopeQuery.Filter scope,
			@Param("after") String after, @Param("limit") int limit, @org.apache.ibatis.annotations.Param("filter") com.lrj.commerce.runtime.api.validation.ListFilter filter);

	/** 兼容内部既有读取调用，缺省时不附加筛选条件。 */
	default List<MerchantApi.View> listScoped(@Param("tenant") String tenant,
			@Param("scope") com.lrj.commerce.runtime.api.scope.ScopeQuery.Filter scope,
			@Param("after") String after, @Param("limit") int limit) {
		return listScoped(tenant, scope, after, limit, com.lrj.commerce.runtime.api.validation.ListFilter.none());
	}

}
