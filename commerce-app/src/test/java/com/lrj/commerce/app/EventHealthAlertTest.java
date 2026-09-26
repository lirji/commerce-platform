package com.lrj.commerce.app;

import com.lrj.commerce.runtime.EventDispatcher;
import com.lrj.commerce.runtime.EventHealthLog;
import com.lrj.commerce.runtime.persistence.EventMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 告警契约：每条规则的触发与不触发边界，日志代码是外部告警接入的稳定标识。 */
class EventHealthAlertTest {
    static EventHealthLog.Snapshot snap(long due,Long oldest,long isolated,long attempted,long failures,long rotationMillis) {return snap(due,oldest,isolated,attempted,failures,rotationMillis,0,0,false);}
    static EventHealthLog.Snapshot snap(long due,Long oldest,long isolated,long attempted,long failures,long rotationMillis,long unrouted,long trips,boolean open) {
        return new EventHealthLog.Snapshot(new EventMapper.Health(due,due,0,isolated,oldest,unrouted,null,0),
            new EventDispatcher.Stats(1,attempted,attempted-failures,failures,0,1,rotationMillis,1,0,0,null,null,0,trips,open));
    }
    @Test void healthyRuntimeRaisesNothing() {
        assertEquals(List.of(),EventHealthLog.evaluate(snap(3,2L,0,100,0,800),snap(5,1L,0,160,1,900),1));
        assertEquals(List.of(),EventHealthLog.evaluate(null,snap(0,null,0,0,0,-1),0));
    }
    @Test void eachRuleHasItsOwnCode() {
        assertEquals(List.of("EVENT_BACKLOG_AGE"),EventHealthLog.evaluate(null,snap(1,301L,0,0,0,-1),0));
        assertEquals(List.of("EVENT_ROTATION_SLOW"),EventHealthLog.evaluate(null,snap(1,1L,0,0,0,300_001),0));
        assertEquals(List.of("EVENT_BACKLOG_GROWING"),EventHealthLog.evaluate(null,snap(1,1L,0,0,0,-1),5));
        assertEquals(List.of("EVENT_QUARANTINE_GROWTH"),EventHealthLog.evaluate(snap(1,1L,2,10,0,1),snap(1,1L,3,20,1,1),0));
        // 20次尝试中5次失败=25%超过20%；19次尝试样本不足不告警。
        assertEquals(List.of("EVENT_FAILURE_RATE"),EventHealthLog.evaluate(snap(1,1L,0,0,0,1),snap(1,1L,0,20,5,1),0));
        assertEquals(List.of(),EventHealthLog.evaluate(snap(1,1L,0,0,0,1),snap(1,1L,0,19,19,1),0));
        assertEquals(List.of("EVENT_NO_PROGRESS"),EventHealthLog.evaluate(snap(4,10L,0,50,0,1),snap(4,70L,0,50,0,1),0));
        assertEquals(List.of(),EventHealthLog.evaluate(snap(0,null,0,50,0,1),snap(4,1L,0,50,0,1),0),"上一周期无积压不算停滞");
        // B1：未声明又无消费者的PENDING事件持续告警；依赖熔断打开或本周期新增熔断告警。
        assertEquals(List.of("EVENT_NO_REQUIRED_CONSUMER"),EventHealthLog.evaluate(null,snap(0,null,0,0,0,1,2,0,false),0));
        assertEquals(List.of("EVENT_DEPENDENCY_UNAVAILABLE"),EventHealthLog.evaluate(null,snap(0,null,0,0,0,1,0,1,true),0));
        assertEquals(List.of("EVENT_DEPENDENCY_UNAVAILABLE"),EventHealthLog.evaluate(snap(0,null,0,0,0,1,0,1,false),snap(0,null,0,0,0,1,0,2,false),0),"两次采样之间熔断过");
        assertEquals(List.of(),EventHealthLog.evaluate(snap(0,null,0,0,0,1,0,2,false),snap(0,null,0,0,0,1,0,2,false),0),"已恢复且无新熔断");
    }
}
