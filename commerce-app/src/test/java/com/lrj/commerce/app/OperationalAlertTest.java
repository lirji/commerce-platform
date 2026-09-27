package com.lrj.commerce.app;

import com.lrj.commerce.runtime.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** §31 告警出口：固定代码经供应商无关的接口发布；默认实现只写日志；发布失败不影响健康评估与车道。 */
@SpringBootTest
class OperationalAlertTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {Phase4Properties.register(registry);}
    @Autowired EventDispatcher events;@Autowired WorkLanes work;@Autowired LaneMonitor monitor;@Autowired Outbox outbox;@Autowired EventReplay replay;@Autowired OperationalAlertPublisher configured;

    @Test void codesArePublishedThroughTheProviderIndependentBoundary() {
        assertNotNull(configured,"未选定供应商时有默认日志出口");
        var failing=mock(RetentionLane.class);
        when(failing.stats()).thenReturn(new RetentionLane.Stats(true,3,0,3,3,0,0,0,0,Instant.now(),1,"DEPENDENCY_UNAVAILABLE"));
        when(failing.lag()).thenReturn(List.of());
        var published=new ArrayList<List<String>>();
        new BackgroundRuntime(events,work,monitor,outbox,replay,failing,(at,codes)->published.add(codes)).logHealth();
        assertEquals(1,published.size());assertTrue(published.getFirst().contains("RETENTION_FAILURE"),published.toString());
        for(var code:published.getFirst())assertTrue(code.matches("[A-Z_]+(:[a-z-]+)?"),"只含固定代码与车道名："+code);
        // 出口失败只记录，不向调度线程抛出。
        assertDoesNotThrow(()->new BackgroundRuntime(events,work,monitor,outbox,replay,failing,(at,codes)->{throw new IllegalStateException("供应商不可用");}).logHealth());
    }
}
