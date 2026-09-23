package com.lrj.commerce.catalog.infrastructure;
import com.lrj.commerce.catalog.api.ProductOperationsApi;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 商品域独占SPU与规格表，所有读取同时绑定tenant和store。 */
@Mapper
public interface ProductMapper {
 record SkuRow(String skuId,String storeId,String title,String unitPrice,long revision,String status,String productId,String specificationsJson) { }
 void insertProduct(@Param("tenant") String tenant,@Param("input") ProductOperationsApi.ProductInput input);
 ProductOperationsApi.Product product(@Param("tenant") String tenant,@Param("store") String store,@Param("id") String id);
 int changeProduct(@Param("tenant") String tenant,@Param("id") String id,@Param("input") ProductOperationsApi.ProductChange input);
 List<ProductOperationsApi.Product> products(@Param("tenant") String tenant,@Param("store") String store,@Param("after") String after,@Param("limit") int limit);
 void insertVariant(@Param("tenant") String tenant,@Param("input") ProductOperationsApi.Variant input,@Param("specifications") String specifications,@Param("hash") String hash);
 SkuRow sku(@Param("tenant") String tenant,@Param("store") String store,@Param("id") String id);
 List<SkuRow> skus(@Param("tenant") String tenant,@Param("store") String store,@Param("after") String after,@Param("limit") int limit);
 int change(@Param("tenant") String tenant,@Param("id") String id,@Param("input") ProductOperationsApi.Change input);
 List<ProductOperationsApi.Revision> history(@Param("tenant") String tenant,@Param("store") String store,@Param("id") String id,@Param("after") long after,@Param("limit") int limit);
}
