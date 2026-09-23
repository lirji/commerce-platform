package com.lrj.commerce.member.api;
import com.lrj.commerce.runtime.api.Actor;
import java.time.Instant;
import java.util.List;
/** 会员交互与成交事实分开，客户端不能伪造交易属性。 */
public interface MemberBehaviorApi {
    enum Kind { BROWSE, ADD_TO_CART }
    record Profile(String birthday,boolean journeyEnabled,long version) { }
    record ProfileChange(long expectedVersion,String birthday,boolean journeyEnabled,String reason) { }
    record Signal(String eventId,Kind kind,String storeId,String skuId) { }
    record Event(long sequenceId,String eventId,Kind kind,String storeId,String skuId,Instant occurredAt) { }
    record Facts(String memberId,int browse30,int cart30,long completedOrders30,String netSpend30,Instant lastOrderAt,Instant lastCartAt,Long daysSinceOrder,long daysSinceJoin,boolean birthdayToday,boolean journeyEnabled) { }
    record Detail(MemberApi.View member,Profile profile,Facts facts) { }
    /** 资料受租户和本人限制。 */
    Detail detail(Actor actor,String member);
    /** 独立资料版本，保留变更原因审计。 */
    Profile profile(Actor actor,String key,String member,ProfileChange input);
    /** app应先验证目录，信号时间由服务器决定。 */
    Event record(Actor actor,Signal input);
    /** 本人或管理员的有界行为明细。 */
    List<Event> events(Actor actor,String member,long after,int limit);
    /** 从同领域净成交来源更新投影，不接受外部请求自报金额。 */
    void projectOrder(String tenant,String order,Instant orderedAt);
    /** 同一时间快照的有界批量事实，供规则/旅程使用。 */
    List<Facts> facts(String tenant,List<String> members,Instant now);
}
