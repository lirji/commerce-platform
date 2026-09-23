package com.lrj.commerce.runtime.api;
import java.time.Instant;
import java.util.Set;
/** 本地事务消费者；禁止在handle中做远程IO，远程工作必须先持久化独立任务。 */
public interface EventHandler {
    record Event(String eventId,String tenantId,String eventType,String aggregateId,long aggregateVersion,String payloadJson,Instant createdAt,int attempts) { }
    String consumer();
    Set<String> types();
    void handle(Event event);
}
