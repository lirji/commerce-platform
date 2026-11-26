package com.lrj.commerce.store.management.infrastructure.persistence;

import com.lrj.commerce.store.management.api.StoreOpeningApi;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 只访问当前租户的开店资料，写入由门店状态及乐观版本约束。 */
@Mapper
public interface StoreOpeningMapper {

	void insert(@Param("tenant") String tenant, @Param("input") StoreOpeningApi.Draft input,
			@Param("category") String category);

	StoreOpeningApi.Opening find(@Param("tenant") String tenant, @Param("id") String id);

	int revise(@Param("tenant") String tenant, @Param("id") String id,
			@Param("input") StoreOpeningApi.Revision input, @Param("category") String category);

	int open(@Param("tenant") String tenant, @Param("id") String id,
			@Param("actor") String actor, @Param("input") StoreOpeningApi.Activation input);

	/** 读取平台字典的二级叶子，两级都需要启用。 */
	List<StoreOpeningApi.Category> categories();

	/** 锁定有效类目及父级直到写入结束，避免审核记录引用已停用定义。 */
	String categoryLabel(@Param("code") String code);
}
