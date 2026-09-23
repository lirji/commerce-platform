package com.lrj.commerce.catalog.api;
import com.lrj.commerce.runtime.api.Actor;
import java.util.List;
import java.time.Instant;

/** 经营商品端口与会员销售目录分离，所有操作验证店铺资源授权。 */
public interface ProductOperationsApi {
 record ProductInput(String productId,String storeId,String title,String category,String brand) { }
 record Product(String productId,String storeId,String title,String category,String brand,long version) { }
 record ProductChange(String storeId,long expectedVersion,String title,String category,String brand) { }
 record Specification(String name,String value) { }
 record Variant(String skuId,String productId,String storeId,String title,String unitPrice,List<Specification> specifications) { }
 record Sku(String skuId,String storeId,String title,String unitPrice,long revision,String status,String productId,List<Specification> specifications) { }
 record Change(String storeId,long expectedVersion,String title,String unitPrice,String status,String reason) { }
 record Revision(long revision,String title,String unitPrice,String status,String reason,String actorId,Instant createdAt) { }
 Product create(Actor actor,String key,ProductInput input);
 Product changeProduct(Actor actor,String key,String id,ProductChange input);
 List<Product> products(Actor actor,String storeId,String after,int limit);
 Sku variant(Actor actor,String key,Variant input);
 Sku change(Actor actor,String key,String id,Change input);
 List<Sku> skus(Actor actor,String storeId,String after,int limit);
 List<Revision> history(Actor actor,String storeId,String id,long after,int limit);
}
