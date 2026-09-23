package com.lrj.commerce.catalog.api;
import com.lrj.commerce.runtime.api.Actor;
import java.time.Instant;
import java.util.List;
/** 固定输入的批量经营任务，取消只停止未来效果。 */
public interface CatalogJobApi {
 enum Action { PRICE,PUBLISH,UNPUBLISH }
 record Target(String skuId,long expectedRevision,String unitPrice) { }
 record Create(String jobId,String storeId,String name,Action action,Instant runAt,Instant deadline,List<Target> targets,String reason) { }
 record View(Create definition,String status,int processed,int succeeded,int conflicted,int attempts,String errorCode,long version,String creatorId) { }
 record Item(int itemIndex,String skuId,String status,long expectedRevision,Long actualRevision,String reason,Instant processedAt) { }
 record Control(String storeId,long expectedVersion,String action,String reason) { }
 View create(Actor actor,String key,Create input);
 List<View> list(Actor actor,String store,String after,int limit);
 List<Item> items(Actor actor,String store,String id,int after,int limit);
 View control(Actor actor,String key,String id,Control input);
 int pump(Actor actor,String store);
 int tick();
}
