package com.lrj.commerce.store.management.infrastructure.persistence;

import com.lrj.commerce.store.management.api.StoreOpeningApi;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只访问当前租户的开店资料，写入由门店状态及乐观版本约束。 */
@Mapper
public interface StoreOpeningMapper {

	void insert(@Param("tenant") String tenant, @Param("input") StoreOpeningApi.Draft input);

	StoreOpeningApi.Opening find(@Param("tenant") String tenant, @Param("id") String id);

	int revise(@Param("tenant") String tenant, @Param("id") String id,
			@Param("input") StoreOpeningApi.Revision input);

	int open(@Param("tenant") String tenant, @Param("id") String id,
			@Param("actor") String actor, @Param("input") StoreOpeningApi.Activation input);
}
