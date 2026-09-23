package com.lrj.commerce.benefit.api;
import com.lrj.commerce.runtime.api.Actor;
import java.time.Instant;
import java.util.List;
/** 内部整数权益台账，不是储值余额；外部权益渠道明确另设适配端口。 */
public interface EntitlementApi {
    /** 稳定状态码，不持久化ordinal，新增状态必须同步合法迁移与数据库约束。 */
    enum State {
        RESERVED("RESERVED"),REQUESTED("REQUESTED"),AVAILABLE("AVAILABLE"),CONSUMED("CONSUMED"),CANCELLED("CANCELLED"),REVOKED("REVOKED"),COMPENSATION_REQUIRED("COMPENSATION_REQUIRED"),COMPENSATED("COMPENSATED");
        private final String code;
        State(String code){this.code=code;}
        @com.fasterxml.jackson.annotation.JsonValue public String getCode(){return code;}
    }
    record Ref(String benefitId,long version) { }
    record Definition(String benefitId,long version,String storeId,String name,int units,int quota,Instant validFrom,Instant validTo,int validityDays) { }
    record DefinitionView(Definition content,int reserved,int issued) { }
    record View(String grantId,String orderId,String memberId,String benefitId,long benefitVersion,String name,State status,int units,int remainingUnits,int debtUnits,Instant expiresAt,long version,String sourceType,String sourceId) { }
    record Ledger(String entryId,String action,int units,int balance,String reference,Instant createdAt) { }
    record Consume(int units) { }
    record Resolution(String resolution,String reference) { }
    DefinitionView create(Actor actor,String key,Definition input);
    List<DefinitionView> definitions(Actor actor,String store,String after,int limit);
    void validateBinding(String tenant,String store,Ref ref,Instant from,Instant to);
    void reserveOrder(Actor actor,String order,String member,String store,Ref ref);
    void confirmOrder(String tenant,String order);
    void releaseOrder(String tenant,String order);
    void reverseOrder(String tenant,String order);
    List<View> wallet(Actor actor,String after,int limit);
    List<View> adminList(Actor actor,String after,int limit);
    List<Ledger> ledger(Actor actor,String grant,String after,int limit);
    View consume(Actor actor,String key,String grant,Consume input);
    View resolve(Actor actor,String key,String grant,Resolution input);
    /** 旅程节点本地事务中受理权益；来源标识和订单关联各自独立。 */
    View grantFromJourney(String tenant,String member,String store,String effectId,String order,Ref ref);
}
