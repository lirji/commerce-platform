package com.lrj.commerce.fulfillment.api;
import com.lrj.commerce.runtime.api.Actor;
import java.util.List;
/** 履约单与售后阻拦共用数据库行锁，避免退款获批后又发货。 */
public interface FulfillmentApi {
    record View(String orderId,String status,String trackingNo,String provider,boolean blocked,long version) { }
    record Ship(String trackingNo) { }
    View read(Actor actor,String order);
    List<View> list(Actor actor,String after,int limit);
    View ship(Actor actor,String key,String order,Ship input);
    View deliver(Actor actor,String key,String order);
    /** 售后在同事务内先锁履约，再创建申请；返回是否必须退货。 */
    boolean holdForAftersale(String tenant,String order);
    /** 驳回解除阻拦；未发货退款成功后永久取消履约。 */
    void finishAftersale(String tenant,String order,boolean refunded);
}
