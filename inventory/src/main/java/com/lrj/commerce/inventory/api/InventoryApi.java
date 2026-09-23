package com.lrj.commerce.inventory.api;
import com.lrj.commerce.runtime.api.Actor;
import java.util.List;

/** 可售额度与仓内实物库存分开；本域最终决定能否预占。 */
public interface InventoryApi {
    record Receipt(String storeId,String skuId,int quantity) { }
    record Stock(String storeId,String skuId,long available,long held,long sold,long version) { }
    /** 管理入库通过幂等命令增加额度。 */
    Stock receive(Actor actor,String key,Receipt input);
    /** 店铺维度有界管理查询。 */
    List<Stock> list(Actor actor,String storeId,String after,int limit);
    /** 必须加入调用方本地事务，不跨网络。 */
    void reserve(Actor actor,String orderId,String storeId,String skuId,int quantity);
    /** 同一订单状态锁下确认预占，重复不会二次扣减。 */
    void confirm(String tenantId,String orderId);
    /** 只释放未确认预占，资金未知期间不得调用。 */
    void release(String tenantId,String orderId);
    /** 售后可信收货，按case/SKU防止重复回补原已确认库存。 */
    void returnItems(String tenant,String order,String caseId,String sku,int quantity);
}
