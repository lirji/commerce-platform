package com.lrj.commerce.catalog.product.api;

import com.lrj.commerce.runtime.api.validation.ListFilter;
import com.lrj.commerce.runtime.api.identity.Actor;
import java.util.List;
import java.time.Instant;

/** 经营商品端口与会员销售目录分离，所有操作验证店铺资源授权。 */
public interface ProductOperationsApi {

	record ProductInput(String productId, String storeId, String title, String category, String brand) {
	}

	record Product(String productId, String storeId, String title, String category, String brand, long version) {
	}

	record ProductChange(String storeId, long expectedVersion, String title, String category, String brand) {
	}

	record Specification(String name, String value) {
	}

	record Variant(String skuId, String productId, String storeId, String title, String unitPrice,
			List<Specification> specifications) {
	}

	record Sku(String skuId, String storeId, String title, String unitPrice, long revision, String status,
			String productId, List<Specification> specifications) {
	}

	record Change(String storeId, long expectedVersion, String title, String unitPrice, String status, String reason) {
	}

	record Revision(long revision, String title, String unitPrice, String status, String reason, String actorId,
			Instant createdAt) {
	}

	Product create(Actor actor, String key, ProductInput input);

	Product changeProduct(Actor actor, String key, String id, ProductChange input);

	List<Product> products(Actor actor, String storeId, String after, int limit);

	/** 只读查询条件，不改变既有数据权限或业务记录。 */
	List<Product> products(Actor actor, String storeId, String after, int limit, ListFilter filter);

	Sku variant(Actor actor, String key, Variant input);

	Sku change(Actor actor, String key, String id, Change input);

	List<Sku> skus(Actor actor, String storeId, String after, int limit);

	/** 只读查询条件，不改变既有数据权限或业务记录。 */
	List<Sku> skus(Actor actor, String storeId, String after, int limit, ListFilter filter);

	List<Revision> history(Actor actor, String storeId, String id, long after, int limit);

}
