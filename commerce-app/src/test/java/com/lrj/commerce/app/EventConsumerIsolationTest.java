package com.lrj.commerce.app;

import com.lrj.commerce.runtime.EventDispatcher;
import com.lrj.commerce.runtime.api.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 同一事件的多个消费者各自提交，一个消费者失败不能回滚或阻塞其他消费者。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class EventConsumerIsolationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        String url=System.getenv("COMMERCE_TEST_DB_URL");
        if(url==null||!url.contains("/commerce_test_20260923?")) throw new IllegalStateException("必须显式指定本项目隔离测试库");
        registry.add("spring.datasource.url",()->url);
        registry.add("commerce.sandbox-enabled",()->true);
        registry.add("commerce.workers-enabled",()->false);
        registry.add("spring.datasource.username",()->System.getenv("COMMERCE_DB_USER"));
        registry.add("spring.datasource.password",()->System.getenv("COMMERCE_DB_PASSWORD"));
    }
    static final String TYPE="test.isolation.v1";
    static final Map<String,Integer> handled=new ConcurrentHashMap<>();
    static final Set<String> failing=ConcurrentHashMap.newKeySet();

    /** 仅本测试上下文注册的消费者，事件类型不与业务事件重叠。 */
    @TestConfiguration static class Consumers {
        @Bean EventHandler isolationHealthy() {return handler("test-isolation-healthy");}
        @Bean EventHandler isolationFailing() {return handler("test-isolation-failing");}
        private static EventHandler handler(String consumer) {
            return new EventHandler() {
                public String consumer(){return consumer;}
                public Set<String> types(){return Set.of(TYPE);}
                public void handle(Event event){
                    if(failing.contains(consumer))throw new IllegalStateException("故障注入");
                    handled.merge(consumer+"/"+event.eventId(),1,Integer::sum);
                }
            };
        }
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired EventDispatcher dispatcher;
    private String tenant,event;
    private Actor admin;

    @BeforeEach void event() {
        tenant="t-"+UUID.randomUUID();event=UUID.randomUUID().toString();admin=new Actor(tenant,"admin",Actor.Role.ADMIN);
        jdbc.update("INSERT INTO platform_event(event_id,tenant_id,event_type,aggregate_id,aggregate_version,payload_json,status) VALUES(?,?,?,?,1,'{}','PENDING')",event,tenant,TYPE,event);
        failing.clear();
    }
    @AfterEach void clear() {failing.clear();}

    @Test void failingConsumerDoesNotRollBackOrRepeatHealthySibling() {
        failing.add("test-isolation-failing");
        assertEquals(0,dispatcher.pump(admin));
        // 健康消费者的效果与Inbox已提交；失败消费者没有Inbox，事件保留待重试。
        assertEquals(1,handled.get("test-isolation-healthy/"+event));
        assertEquals(List.of("test-isolation-healthy"),inbox());
        assertEquals("PENDING",status());assertEquals(1,attempts());

        failing.clear();
        jdbc.update("UPDATE platform_event SET available_at=CURRENT_TIMESTAMP(3) WHERE event_id=?",event);
        assertEquals(1,dispatcher.pump(admin));
        // 重试只执行尚未成功的消费者。
        assertEquals(1,handled.get("test-isolation-healthy/"+event));
        assertEquals(1,handled.get("test-isolation-failing/"+event));
        assertEquals(List.of("test-isolation-failing","test-isolation-healthy"),inbox());
        assertEquals("DELIVERED",status());
    }
    @Test void isolatedEventOnlyBlocksTheFailingConsumer() {
        failing.add("test-isolation-failing");
        for(int i=0;i<6&&!status().equals("ISOLATED");i++) {
            jdbc.update("UPDATE platform_event SET available_at=CURRENT_TIMESTAMP(3) WHERE event_id=?",event);dispatcher.pump(admin);
        }
        assertEquals("ISOLATED",status());
        assertEquals(1,handled.get("test-isolation-healthy/"+event));
        assertEquals(List.of("test-isolation-healthy"),inbox());
    }
    private List<String> inbox() {return jdbc.queryForList("SELECT consumer_id FROM platform_inbox WHERE event_id=? ORDER BY consumer_id",String.class,event);}
    private String status() {return jdbc.queryForObject("SELECT status FROM platform_event WHERE event_id=?",String.class,event);}
    private int attempts() {return jdbc.queryForObject("SELECT attempts FROM platform_event WHERE event_id=?",Integer.class,event);}
}
