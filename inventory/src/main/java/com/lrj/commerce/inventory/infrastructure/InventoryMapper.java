package com.lrj.commerce.inventory.infrastructure;
import com.lrj.commerce.inventory.api.InventoryApi.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 库存条件更新及预占唯一键承担最终完整性。 */
@Mapper
public interface InventoryMapper {
    record Hold(String storeId,String skuId,int quantity,String status) { }
    void receive(@Param("tenant") String tenant,@Param("input") Receipt input);
    Stock find(@Param("tenant") String tenant,@Param("store") String store,@Param("sku") String sku);
    List<Stock> list(@Param("tenant") String tenant,@Param("store") String store,@Param("after") String after,@Param("limit") int limit);
    int reserve(@Param("tenant") String tenant,@Param("store") String store,@Param("sku") String sku,@Param("quantity") int quantity);
    void insertHold(@Param("tenant") String tenant,@Param("order") String order,@Param("store") String store,@Param("sku") String sku,@Param("quantity") int quantity);
    List<Hold> holds(@Param("tenant") String tenant,@Param("order") String order);
    int terminal(@Param("tenant") String tenant,@Param("order") String order,@Param("sku") String sku,@Param("status") String status);
    int confirmStock(@Param("tenant") String tenant,@Param("hold") Hold hold);
    int releaseStock(@Param("tenant") String tenant,@Param("hold") Hold hold);
}
