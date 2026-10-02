package com.lrj.commerce.catalog.assortment.infrastructure.persistence;

import com.lrj.commerce.catalog.assortment.api.CatalogApi;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 批量读取商品，避免报价逐行查询形成N+1。 */
@Mapper
public interface CatalogMapper {

	CatalogApi.Stats stats(String tenant, String store);

    /** 商品权限 SQL 聚合与规格归属使用相同事实列，空范围拒绝。 */
    CatalogApi.Stats statsScoped(@Param("tenant") String tenant, @Param("store") String store,
            @Param("scope") com.lrj.commerce.runtime.api.scope.ScopeQuery.Filter scope);

	List<CatalogApi.Price> priced(String tenant, String store, List<String> ids, String channel, java.time.Instant now);

	void snapshot(@Param("tenant") String tenant, @Param("id") String id, @Param("reason") String reason,
			@Param("actor") String actor);

	void insert(@Param("tenant") String tenant, @Param("input") CatalogApi.Create input);

	List<CatalogApi.View> list(@Param("tenant") String tenant, @Param("store") String store,
			@Param("after") String after, @Param("limit") int limit);

	List<CatalogApi.View> batch(@Param("tenant") String tenant, @Param("store") String store,
			@Param("ids") List<String> ids);

}
