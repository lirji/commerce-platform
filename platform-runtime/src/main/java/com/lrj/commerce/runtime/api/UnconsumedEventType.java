package com.lrj.commerce.runtime.api;

/**
 * 发布方声明本进程没有消费者的事件类型：写入即为终态SKIPPED，保留行供审计与管理员重放，不算投递。
 * 未声明又没有消费者的类型视为缺少必需消费者，保持PENDING并告警，绝不静默跳过；已有消费者的类型不得声明。
 */
public record UnconsumedEventType(String type,Reason reason) {
    public enum Reason { NO_REGISTERED_CONSUMER, OBSOLETE_EVENT_TYPE }
    public UnconsumedEventType {if(type==null||type.isBlank()||type.length()>64||reason==null)throw new IllegalArgumentException("无消费者事件声明无效");}
}
