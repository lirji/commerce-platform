package com.lrj.commerce.runtime.api;
import java.time.Instant;
import java.util.Set;
/** 本地事务消费者；禁止在handle中做远程IO，远程工作必须先持久化独立任务。 */
public interface EventHandler {
    /** attempts为非瞬时失败次数（毒事件预算），transientAttempts为依赖不可用等瞬时失败次数。 */
    record Event(String eventId,String tenantId,String eventType,String aggregateId,long aggregateVersion,String payloadJson,Instant createdAt,int attempts,int transientAttempts) { }
    String consumer();
    Set<String> types();
    void handle(Event event);
}
