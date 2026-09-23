package com.lrj.commerce.catalog.api;
import com.lrj.commerce.runtime.api.Actor;
import java.util.List;

/** 商品权威价格与版本只能通过目录端口提供给交易。 */
public interface CatalogApi {
    record Stats(long total,long active,long frozen) { }
    /** 当前门店规格总量和销售状态分布。 */
    Stats stats(Actor actor,String store);
    record Create(String skuId,String storeId,String title,String unitPrice) { }
    record View(String skuId,String storeId,String title,String unitPrice,long revision,String status) { }
    record Price(String skuId,String storeId,String title,String unitPrice,long revision,long channelPriceVersion,java.time.Instant validTo) { }
    /** 批量提供当前可信渠道售价及其有效期。 */
    List<Price> priced(Actor actor,String storeId,List<String> skuIds,java.time.Instant now);
    View create(Actor actor,String key,Create input);
    List<View> list(Actor actor,String storeId,String after,int limit);
    List<View> published(Actor actor,String storeId,List<String> skuIds);
}
