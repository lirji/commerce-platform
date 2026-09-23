package com.lrj.commerce.store.api;
import com.lrj.commerce.runtime.api.Actor;
import java.util.List;

/** 资源授权是店铺域能力，调用方必须同时限定租户和实际资源。 */
public interface StoreAccessApi {
 record Create(String grantId,String actorId,String resourceType,String resourceId,String permission,String reason) { }
 record Change(long expectedVersion,boolean active,String reason) { }
 record Grant(String grantId,String actorId,String resourceType,String resourceId,String permission,boolean active,long version,String reason) { }
 Grant create(Actor actor,String key,Create input);
 Grant change(Actor actor,String key,String id,Change input);
 List<Grant> list(Actor actor,String after,int limit);
 List<StoreApi.View> stores(Actor actor,String after,int limit);
 /** 读取或写入商品前检查；普通会员不能使用经营端口。 */
 void requireCatalog(Actor actor,String storeId);
}
