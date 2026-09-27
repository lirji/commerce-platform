package com.lrj.commerce.app;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * 第四阶段测试共用的上下文属性（相同属性集复用同一Spring上下文）。共享测试库里有其他测试遗留的到期PENDING事件（测试关闭Worker），
 * 因此把重放与保留期清理的实时让路阈值调高；让路行为由专门的测试以小阈值手工构造实例验证。
 */
final class Phase4Properties {
    private Phase4Properties() { }
    static void register(DynamicPropertyRegistry registry) {
        String url=System.getenv("COMMERCE_TEST_DB_URL");
        if(url==null||!url.contains("/commerce_test_20260923?")) throw new IllegalStateException("必须显式指定本项目隔离测试库");
        registry.add("spring.datasource.url",()->url);
        registry.add("commerce.sandbox-enabled",()->true);
        registry.add("commerce.workers-enabled",()->false);
        registry.add("spring.datasource.username",()->System.getenv("COMMERCE_DB_USER"));
        registry.add("spring.datasource.password",()->System.getenv("COMMERCE_DB_PASSWORD"));
        registry.add("commerce.replay.live-yield",()->1_000_000);
        registry.add("commerce.retention.live-yield",()->1_000_000);
    }
}
