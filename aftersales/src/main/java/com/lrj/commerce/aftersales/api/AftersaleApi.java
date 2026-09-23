package com.lrj.commerce.aftersales.api;
import com.lrj.commerce.runtime.api.Actor;
import java.util.List;
/** 售后不回退原订单和支付；部分退款绑定已固化行金额。 */
public interface AftersaleApi {
    record Item(String skuId,int quantity) { }
    record Request(String orderId,String reason,List<Item> items) { }
    record Line(String skuId,int quantity,String refundAmount) { }
    record View(String caseId,String orderId,String memberId,String status,boolean returnRequired,String refundAmount,String refundId,long version,List<Line> items) { }
    record Completion(String caseId,String orderId,String refundId,boolean fullReturn) { }
    View request(Actor actor,String key,Request input);
    View read(Actor actor,String id);
    List<View> list(Actor actor,String after,int limit);
    List<View> adminList(Actor actor,String after,int limit);
    View approve(Actor actor,String key,String id);
    View reject(Actor actor,String key,String id);
    View receiveReturn(Actor actor,String key,String id);
    /** 内部事务复核原订单是否已经全部退货退款完成。 */
    boolean fullyReturned(String tenant,String order);
}
