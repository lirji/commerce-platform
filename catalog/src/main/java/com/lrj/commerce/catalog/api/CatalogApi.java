package com.lrj.commerce.catalog.api;
import com.lrj.commerce.runtime.api.Actor;
import java.util.List;

/** 商品权威价格与版本只能通过目录端口提供给交易。 */
public interface CatalogApi {
    record Create(String skuId,String storeId,String title,String unitPrice) { }
    record View(String skuId,String storeId,String title,String unitPrice,long revision,String status) { }
    View create(Actor actor,String key,Create input);
    List<View> list(Actor actor,String storeId,String after,int limit);
    List<View> published(Actor actor,String storeId,List<String> skuIds);
}
