package com.lrj.commerce.architecture;

import com.lrj.commerce.kernel.Money;
import com.lrj.commerce.marketing.api.DecisionPort;
import com.lrj.commerce.order.domain.OrderLifecycle;
import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.spi.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

/** 检查真实编译产物依赖，不用预设目录列表冒充模块隔离已经成立。 */
class ModuleBoundaryTest {
    @Test void compiledDomainDependenciesRespectOwnershipAndApiDirection() throws Exception {
        var diagnostics = new StringWriter();
        var arguments = new java.util.ArrayList<>(List.of("-verbose:class", "-filter:none"));
        for (Class<?> type : List.of(Money.class, DecisionPort.class, OrderLifecycle.class)) {
            arguments.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        int code = ToolProvider.findFirst("jdeps").orElseThrow().run(new PrintWriter(diagnostics),
            new PrintWriter(diagnostics), arguments.toArray(String[]::new));
        assertEquals(0, code, diagnostics.toString());
        int edges = 0;
        for (String line : diagnostics.toString().split("\\R")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 3 || !parts[1].equals("->") || !parts[0].startsWith("com.lrj.commerce.")) continue;
            edges++;
            String source = parts[0], target = parts[2];
            String owner = source.split("\\.")[3];
            if (target.startsWith("com.lrj.commerce.")) {
                String targetOwner = target.split("\\.")[3];
                assertTrue(owner.equals(targetOwner) || targetOwner.equals("kernel"), line);
                if (source.contains(".api.")) {
                    assertFalse(target.contains(".domain.") || target.contains(".application."), line);
                }
                if (source.contains(".domain.")) assertFalse(target.contains(".application."), line);
            } else {
                assertTrue(target.startsWith("java.lang.") || target.startsWith("java.math.")
                    || target.startsWith("java.util.") || target.startsWith("java.time."), line);
                assertFalse(target.startsWith("java.lang.reflect.") || target.equals("java.lang.Runtime")
                    || target.equals("java.lang.ProcessBuilder") || target.startsWith("java.util.spi."), line);
            }
        }
        assertTrue(edges > 50, "未扫描到足够的真实类依赖，不能空跑通过");
    }
}
