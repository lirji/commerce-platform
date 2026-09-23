package com.lrj.commerce.journey.api;
import com.lrj.commerce.runtime.api.Actor;
import com.lrj.commerce.campaign.api.RuleNode;
import com.lrj.commerce.benefit.api.EntitlementApi;
import java.time.Instant;
import java.util.List;
/** 旅程只执行已批准的有限节点，版本和持久检查点决定恢复行为。 */
public interface JourneyApi {
    enum Kind { WAIT, DECIDE, GRANT, COUPON, NOTIFY, END }
    enum Trigger { MANUAL, ORDER_PAID, MEMBER_REGISTERED, LEVEL_CHANGED, SEGMENT_ENTERED, BIRTHDAY, DORMANT, REPURCHASE, CART_ABANDONED }
    enum State { RUNNING, WAITING, ISOLATED, COMPLETED, CANCELLED, TIMED_OUT }
    record CouponRef(String definitionId,long version) { }
    record Node(String id,Kind kind,Integer seconds,String next,RuleNode rule,String yesNext,String noNext,EntitlementApi.Ref benefit,String title,String body,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) CouponRef coupon) {
        public Node(String id,Kind kind,Integer seconds,String next,RuleNode rule,String yesNext,String noNext,EntitlementApi.Ref benefit,String title,String body){this(id,kind,seconds,next,rule,yesNext,noNext,benefit,title,body,null);}
    }
    record Controls(String segmentId,RuleNode entryRule,int maxEntries,int entryWindowSeconds,int notificationLimit,int notificationWindowSeconds) { }
    record Lifecycle(int thresholdDays,int cartDelaySeconds,int scanIntervalSeconds,int conversionWindowDays) { }
    record Definition(String journeyId,long version,String storeId,String name,Trigger trigger,Instant validFrom,Instant validTo,int maxDurationSeconds,String entry,List<Node> nodes,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Controls controls,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Lifecycle lifecycle) {
        public Definition(String journeyId,long version,String storeId,String name,Trigger trigger,Instant validFrom,Instant validTo,int maxDurationSeconds,String entry,List<Node> nodes,Controls controls){this(journeyId,version,storeId,name,trigger,validFrom,validTo,maxDurationSeconds,entry,nodes,controls,null);}
        public Definition(String journeyId,long version,String storeId,String name,Trigger trigger,Instant validFrom,Instant validTo,int maxDurationSeconds,String entry,List<Node> nodes){this(journeyId,version,storeId,name,trigger,validFrom,validTo,maxDurationSeconds,entry,nodes,null);}
    }
    record Scan(String journeyId,long journeyVersion,String status,String memberCursor,Instant createdBefore,Instant nextDue,long scanned,long enrolled,int attempts,String errorCode,long version) { }
    record ScanRetry(long expectedVersion,String reason) { }
    /** 持久扫描状态与人工恢复均有界且受租户约束。 */
    List<Scan> scans(Actor actor,String after,int limit);
    Scan retryScan(Actor actor,String key,String id,long version,ScanRetry input);
    record EffectSummary(String journeyId,long enrolled,long completed,long notified,long entrySuppressed,long notificationSuppressed) { }
    record View(Definition content,String status,long lockVersion) { }
    record Start(String journeyId,long version,String memberId,String eventKey) { }
    record Instance(String instanceId,String journeyId,long journeyVersion,String memberId,String orderId,String currentNode,State status,Instant dueAt,Instant deadline,int steps,int attempts,String result,long version) { }
    record Notification(String notificationId,String title,String body,Instant createdAt) { }
    /** 创建不可变草稿并检查DAG和权益引用。 */
    View create(Actor actor,String key,Definition input);
    /** 内容只能通过创建新版本修改。 */
    List<View> definitions(Actor actor,String after,int limit);
    /** 审批和发布包含预期版本与审计。 */
    View change(Actor actor,String key,String id,long version,long expected,String action);
    /** 手工入组只允许手工触发版本，eventKey在版本内唯一。 */
    Instance enroll(Actor actor,String key,Start input);
    /** 管理员与本人分别限定查询范围。 */
    List<Instance> instances(Actor actor,String after,int limit);
    /** 取消仅停止后续节点；已有业务效果不会被隐藏。 */
    Instance control(Actor actor,String key,String id,String action);
    /** 站内触达从数据库读取，不能指定他人的会员ID。 */
    List<Notification> notifications(Actor actor,String after,int limit);
    int pump(Actor actor);
    int tick();
    /** 全额退货先取消后续节点，再由权益模块冲正已产生效果。 */
    void cancelForOrder(String tenant,String order);
    /** 执行指标按事件发生时间聚合，不推断销售归因。 */
    List<EffectSummary> effects(Actor actor,String store,Instant from,Instant to,String after,int limit);
}
