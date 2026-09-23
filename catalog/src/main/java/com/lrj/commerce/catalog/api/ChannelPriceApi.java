package com.lrj.commerce.catalog.api;
import com.lrj.commerce.runtime.api.Actor;
import java.time.Instant;
import java.util.List;
/** 渠道价格独立版本化；销售渠道由认证身份确定。 */
public interface ChannelPriceApi {
 record Change(String storeId,Actor.Channel channel,long expectedVersion,String unitPrice,Instant validFrom,Instant validTo,boolean active,String reason) { }
 record Price(String skuId,String storeId,Actor.Channel channel,long version,String unitPrice,Instant validFrom,Instant validTo,boolean active,String reason,String actorId,Instant changedAt) { }
 Price change(Actor actor,String key,String sku,Change input);
 List<Price> list(Actor actor,String store,String sku);
 List<Price> history(Actor actor,String store,String sku,Actor.Channel channel,long after,int limit);
}
