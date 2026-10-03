package com.lrj.commerce.catalog.product.infrastructure.persistence;

import com.lrj.commerce.catalog.product.api.ProductOperationsApi;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 商品域独占SPU与规格表，所有读取同时绑定tenant和store。 */
@Mapper
public interface ProductMapper {

	record SkuRow(String skuId, String storeId, String title, String unitPrice, long revision, String status,
			String productId, String specificationsJson) {
	}

	void insertProduct(@Param("tenant") String tenant, @Param("input") ProductOperationsApi.ProductInput input);

	ProductOperationsApi.Product productLock(String tenant, String store, String id);

	ProductOperationsApi.Product product(@Param("tenant") String tenant, @Param("store") String store,
			@Param("id") String id);

	int changeProduct(@Param("tenant") String tenant, @Param("id") String id,
			@Param("input") ProductOperationsApi.ProductChange input);

	/** 授权范围和乐观版本同一UPDATE，旧决策超时不得提交。 */
	int changeScopedProduct(@Param("tenant") String tenant,@Param("id") String id,
		@Param("input") ProductOperationsApi.ProductChange input,@Param("scope") com.lrj.commerce.runtime.api.scope.ScopeQuery.Filter scope,
		@Param("deadline") java.time.Instant deadline);
	List<ProductOperationsApi.Product> products(@Param("tenant") String tenant, @Param("store") String store,
			@Param("after") String after, @Param("limit") int limit, @org.apache.ibatis.annotations.Param("filter") com.lrj.commerce.runtime.api.validation.ListFilter filter);

	/** 兼容内部既有读取调用，缺省时不附加筛选条件。 */
	default List<ProductOperationsApi.Product> products(@Param("tenant") String tenant, @Param("store") String store,
			@Param("after") String after, @Param("limit") int limit) {
		return products(tenant, store, after, limit, com.lrj.commerce.runtime.api.validation.ListFilter.none());
	}

	void insertVariant(@Param("tenant") String tenant, @Param("input") ProductOperationsApi.Variant input,
			@Param("specifications") String specifications, @Param("hash") String hash);

	SkuRow skuLock(String tenant, String store, String id);

	SkuRow sku(@Param("tenant") String tenant, @Param("store") String store, @Param("id") String id);

	List<SkuRow> skus(@Param("tenant") String tenant, @Param("store") String store, @Param("after") String after,
			@Param("limit") int limit, @org.apache.ibatis.annotations.Param("filter") com.lrj.commerce.runtime.api.validation.ListFilter filter);

	/** 兼容内部既有读取调用，缺省时不附加筛选条件。 */
	default List<SkuRow> skus(@Param("tenant") String tenant, @Param("store") String store, @Param("after") String after,
			@Param("limit") int limit) {
		return skus(tenant, store, after, limit, com.lrj.commerce.runtime.api.validation.ListFilter.none());
	}

	int change(@Param("tenant") String tenant, @Param("id") String id,
			@Param("input") ProductOperationsApi.Change input);

	List<ProductOperationsApi.Revision> history(@Param("tenant") String tenant, @Param("store") String store,
			@Param("id") String id, @Param("after") long after, @Param("limit") int limit);

}
