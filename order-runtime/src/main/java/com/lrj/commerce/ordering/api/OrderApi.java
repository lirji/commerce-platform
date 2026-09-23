package com.lrj.commerce.ordering.api;
import com.lrj.commerce.runtime.api.Actor;
import com.lrj.commerce.trade.api.QuoteApi;
import java.time.Instant;
import java.util.List;

/** 订单对外只返回商业快照，收货地址不通过普通查询泄露。 */
public interface OrderApi {
    record Address(String recipient,String phone,String detail) { }
    record Create(String quoteId,Address address) { }
    record View(String orderId,String memberId,String storeId,String merchantId,String quoteId,String payable,String status,String paymentKind,long version,Instant createdAt,Instant expiresAt,List<QuoteApi.Line> items,Actor.Channel channel) {
        public View { channel=channel==null?Actor.Channel.WEB:channel;items=List.copyOf(items); }
    }
    /** 报价消费、库存、订单及事件必须原子提交。 */
    View create(Actor actor,String key,Create input);
    /** 本人历史订单读取不重新定价。 */
    View read(Actor actor,String orderId);
    /** 稳定ID游标分页，明细只在单笔接口提供。 */
    List<View> list(Actor actor,String after,int limit);
    /** 支付未知时只能进入CLOSING，不能释放库存。 */
    View cancel(Actor actor,String key,String orderId);
    /** 支付开始与本地支付意图同事务；先锁订单统一资金相关锁顺序。 */
    View beginPayment(Actor actor,String id);
    /** 可信支付事实消费入口，仅内部调用，不暴露HTTP任意事件。 */
    View paymentFact(String tenant,String id,String amount,boolean paid);
    /** 内部按租户查询，不接受客户端替换租户。 */
    View internalRead(String tenant,String id);
    /** 生命周期仅判断本门店加购后的已付订单，不暴露订单资料。 */
    boolean hasPaidSince(String tenant,String member,String store,Instant since);
    /** 到期只请求取消，支付未知必须保留库存。 */
    int expire(Actor actor,String key);
    /** 履约可信事实推进生命周期，必须加入调用者本地事务。 */
    View fulfillmentFact(String tenant,String id,boolean delivered);
    /** 管理查询仅同租户投影，不暴露收货地址。 */
    List<View> adminList(Actor actor,String after,int limit);
    View adminRead(Actor actor,String id);
}
