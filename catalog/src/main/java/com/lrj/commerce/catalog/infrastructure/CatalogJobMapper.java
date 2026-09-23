package com.lrj.commerce.catalog.infrastructure;
import com.lrj.commerce.catalog.api.CatalogJobApi.*;
import org.apache.ibatis.annotations.Mapper;
import java.time.Instant;
import java.util.List;
/** 行锁覆盖单项目效果、回执与游标，不长期持有整批事务。 */
@Mapper
public interface CatalogJobMapper {
 record Row(String jobId,String storeId,String creatorJson,String contentJson,String status,Instant availableAt,int cursorIndex,int succeeded,int conflicted,int attempts,String errorCode,long version) { }
 void insert(String tenant,Create input,String creator,String content,Instant runAt);
 Row lock(String tenant,String id);
 List<Row> list(String tenant,String store,String after,int limit);
 List<Item> items(String tenant,String id,int after,int limit);
 void receipt(String tenant,String id,Item item);
 int advance(String tenant,String id,long version,boolean success,boolean complete);
 int status(String tenant,String id,long version,String status,Instant now);
 void failed(String tenant,String id,String code,Instant next);
 List<String> tenants(String after,Instant now);
 String pending(String tenant,String store,Instant now);
}
