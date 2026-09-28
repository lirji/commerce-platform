package com.lrj.commerce.app.iam;

import com.lrj.authz.sdk.*;
import com.lrj.authz.sdk.AccessDeniedException;
import com.lrj.commerce.store.management.api.StoreApi;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/** 按唯一路径切换内部只读试点；启用后该路径只有中央权威，其他路径仍走旧认证。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "commerce.iam.store-read.enabled", havingValue = "true")
public class CentralStoreConfiguration {
    /** 私密配置必须0600且明确app/env，不从浏览器读取服务凭据。 */
    @Bean CentralAccessClient centralStoreClient(Environment environment) throws IOException {
        String filename = environment.getProperty("commerce.iam.store-read.configuration");
        if (filename == null) throw new IllegalStateException("缺少中央门店读取配置");
        Path path = Path.of(filename);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16384
                || !Files.getPosixFilePermissions(path).equals(PosixFilePermissions.fromString("rw-------"))) throw new IllegalStateException("中央配置文件无效");
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(path)) { p.load(reader); }
        if (!"commerce".equals(p.getProperty("central.application"))) throw new IllegalStateException("中央应用绑定无效");
        return new CentralAccessClient(p.getProperty("central.url"), p.getProperty("central.credential"),
                p.getProperty("central.application"), p.getProperty("central.environment"), Duration.ofSeconds(1), Duration.ofSeconds(8));
    }
    /** 公开门店业务API保持原有所有权和数据映射。 */
    @Bean CentralStoreReadService centralStoreRead(CentralAccessClient client, CentralStoreBindingMapper bindings, StoreApi stores, Optional<CentralScopeService> scoped) {
        return new CentralStoreReadService(client, bindings, stores,scoped.orElse(null));
    }
    /** 无OR回退；中央失败不会使用旧管理员凭据继续该试点路径。 */
    @Bean @Order(0)
    SecurityFilterChain centralStoreSecurity(HttpSecurity http, CentralStoreReadService service) throws Exception {
        return http.securityMatcher("/v1/operations/stores").csrf(c -> c.disable())
                .sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new CentralFilter(service), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(c -> c.requestMatchers(HttpMethod.GET, "/v1/operations/stores").hasAuthority("CENTRAL_STORE_READ").anyRequest().denyAll())
                .exceptionHandling(c -> c.authenticationEntryPoint((req, res, e) -> error(res, 401, "UNAUTHENTICATED"))
                        .accessDeniedHandler((req, res, e) -> error(res, 403, "FORBIDDEN"))).build();
    }
    /** 与商城既有错误形状一致，响应不包含原始异常或用户凭据。 */
    static void error(HttpServletResponse response, int status, String code) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JsonCodec.write(Map.of("code", code, "message", code, "traceId", UUID.randomUUID().toString())));
    }
    /** 验证后只授予当前门店读取authority，结束清理线程中的Token证据。 */
    private static final class CentralFilter extends OncePerRequestFilter {
        private final CentralStoreReadService service;
        CentralFilter(CentralStoreReadService service) { this.service = service; }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
            SecurityContextHolder.clearContext();
            response.setHeader("Cache-Control","no-store");
            try {
                String header = single(request, "Authorization"), tenant = single(request, "X-Tenant-Id");
                if (!header.startsWith("Bearer ")) throw new CentralAccessException(401);
                var identity = service.authenticate(header.substring(7), tenant);
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(identity, null, List.of(new SimpleGrantedAuthority("CENTRAL_STORE_READ"))));
                SecurityContextHolder.setContext(context);
            } catch (CentralAccessException e) { error(response, e.status(), e.status() == org.springframework.http.HttpStatus.UNAUTHORIZED.value() ? "UNAUTHENTICATED" : "UNAVAILABLE"); return; }
            catch (AccessDeniedException e) { error(response, 403, "FORBIDDEN"); return; }
            catch (IllegalArgumentException e) { error(response, 400, "INVALID_ARGUMENT"); return; }
            catch (org.springframework.dao.DataAccessException e) { error(response, 503, "UNAVAILABLE"); return; }
            try { chain.doFilter(request, response); }
            finally { SecurityContextHolder.clearContext(); }
        }
        private String single(HttpServletRequest request, String name) {
            var values = request.getHeaders(name);
            if (values == null || !values.hasMoreElements()) throw new CentralAccessException(401);
            String value = values.nextElement();
            if (values.hasMoreElements() || value == null || value.isBlank()) throw new CentralAccessException(401);
            return value;
        }
    }
}
