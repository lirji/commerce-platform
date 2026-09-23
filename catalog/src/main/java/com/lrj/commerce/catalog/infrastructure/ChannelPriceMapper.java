package com.lrj.commerce.catalog.infrastructure;
import com.lrj.commerce.catalog.api.ChannelPriceApi.*;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;
/** SKU行锁下写当前价和不可变历史，唯一键承担最终约束。 */
@Mapper
public interface ChannelPriceMapper {
 Price current(String tenant,String store,String sku,String channel);
 List<Price> list(String tenant,String store,String sku);
 void save(String tenant,Price price);
 void snapshot(String tenant,Price price);
 List<Price> history(String tenant,String store,String sku,String channel,long after,int limit);
}
