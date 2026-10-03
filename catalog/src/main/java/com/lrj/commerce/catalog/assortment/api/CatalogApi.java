package com.lrj.commerce.catalog.assortment.api;

import com.lrj.commerce.runtime.api.validation.ListFilter;
import com.lrj.commerce.runtime.api.identity.Actor;
import java.util.List;

/** 商品权威价格与版本只能通过目录端口提供给交易。 */
public interface CatalogApi {

	record Stats(long total, long active, long frozen) {
	}

	/** 当前门店规格总量和销售状态分布。 */
	Stats stats(Actor actor, String store);

    /** 内部仪表盘只统计已由中央 product.read 证明的商品/门店交集，不向 HTTP 开放过滤器。 */
    Stats statsScoped(Actor actor, com.lrj.commerce.runtime.api.scope.ScopeQuery.Filter scope, String store);

	record Create(String skuId, String storeId, String title, String unitPrice) {
	}

	record View(String skuId, String storeId, String title, String unitPrice, long revision, String status) {
	}

	record Price(String skuId, String storeId, String title, String unitPrice, long revision, long channelPriceVersion,
			java.time.Instant validTo) {
	}

	/** 批量提供当前可信渠道售价及其有效期。 */
	List<Price> priced(Actor actor, String storeId, List<String> skuIds, java.time.Instant now);

	View create(Actor actor, String key, Create input);

	List<View> list(Actor actor, String storeId, String after, int limit);

	/** 只读查询条件，不改变既有数据权限或业务记录。 */
	List<View> list(Actor actor, String storeId, String after, int limit, ListFilter filter);

	List<View> published(Actor actor, String storeId, List<String> skuIds);

}
