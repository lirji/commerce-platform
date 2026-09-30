package com.lrj.commerce.app.iam;

import com.lrj.authz.sdk.*;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/** 仅接管登记的员工方法与路径，用例层路由仍拦截去掉header后的旧入口。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"commerce.iam.store-read.enabled", "commerce.iam.employee.enabled"}, havingValue = "true")
public class CentralEmployeeConfiguration {
    @Bean CentralEmployeeService centralEmployeeService(CentralAccessClient client, CentralStoreBindingMapper bindings, EmployeeAccess access, com.lrj.commerce.store.management.api.StoreApi stores) {
        return new CentralEmployeeService(client, bindings, access, stores);
    }
    /** 独立authority不等价于ROLE_ADMIN，不能进入未登记的管理接口。 */
    @Bean @Order(-3) SecurityFilterChain centralEmployeeSecurity(HttpSecurity http, CentralEmployeeService service) throws Exception {
        return http.securityMatcher(r -> capability(r) != null && r.getHeader("X-Tenant-Id") != null)
                .csrf(c -> c.disable()).sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new EmployeeFilter(service), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(c -> c.anyRequest().hasAuthority("CENTRAL_EMPLOYEE"))
                .exceptionHandling(c -> c.authenticationEntryPoint((req, res, e) -> CentralStoreConfiguration.error(res, 401, "UNAUTHENTICATED"))
                        .accessDeniedHandler((req, res, e) -> CentralStoreConfiguration.error(res, 403, "FORBIDDEN"))).build();
    }
    private static EmployeeAccess.Capability capability(HttpServletRequest r) {
        String path = r.getRequestURI().substring(r.getContextPath().length());
        if ("GET".equals(r.getMethod()) && ("/v1/admin/inventory".equals(path) || "/v1/operations/inventory/actions".equals(path))) return EmployeeAccess.Capability.INVENTORY_READ;
        if ("POST".equals(r.getMethod()) && "/v1/admin/inventory/receipts".equals(path)) return EmployeeAccess.Capability.INVENTORY_RECEIVE;
        if ("/v1/admin/merchants".equals(path)) {
            if ("GET".equals(r.getMethod())) return EmployeeAccess.Capability.MERCHANT_READ;
            if ("POST".equals(r.getMethod())) return EmployeeAccess.Capability.MERCHANT_CREATE;
        }
        if ("/v1/admin/stores".equals(path)) {
            if ("GET".equals(r.getMethod())) return EmployeeAccess.Capability.STORE_DIRECTORY_READ;
            if ("POST".equals(r.getMethod())) return EmployeeAccess.Capability.STORE_CREATE;
        }
        if ("GET".equals(r.getMethod())) {
            if ("/v1/operations/member-cycles/publish-access".equals(path)) return EmployeeAccess.Capability.MEMBER_CYCLE_POLICY_PUBLISH;
            if ("/v1/operations/member-cycles/evaluate-access".equals(path)) return EmployeeAccess.Capability.MEMBER_CYCLE_EVALUATE;
            if ("/v1/operations/member-cycle-benefits/define-access".equals(path)) return EmployeeAccess.Capability.CYCLE_BENEFIT_DEFINE;
            if ("/v1/operations/member-cycle-benefits/grant-access".equals(path)) return EmployeeAccess.Capability.CYCLE_BENEFIT_GRANT;
            if ("/v1/operations/member-behavior/update-access".equals(path)) return EmployeeAccess.Capability.MEMBER_BEHAVIOR_UPDATE;
            if ("/v1/operations/member-behavior/rebuild-access".equals(path)) return EmployeeAccess.Capability.MEMBER_BEHAVIOR_REBUILD;
            if ("/v1/operations/member-tags/define-access".equals(path)) return EmployeeAccess.Capability.MEMBER_TAG_DEFINE;
            if ("/v1/operations/member-tags/assign-access".equals(path)) return EmployeeAccess.Capability.MEMBER_TAG_ASSIGN;
            if ("/v1/operations/member-growth/policy-access".equals(path)) return EmployeeAccess.Capability.GROWTH_POLICY_PUBLISH;
            if ("/v1/operations/member-growth/adjust-access".equals(path)) return EmployeeAccess.Capability.GROWTH_ADJUST;
            if ("/v1/operations/member-growth/recalculate-access".equals(path)) return EmployeeAccess.Capability.GROWTH_RECALCULATE;
            if ("/v1/operations/members/create-access".equals(path)) return EmployeeAccess.Capability.MEMBER_CREATE;
            if ("/v1/operations/members/profile-access".equals(path)) return EmployeeAccess.Capability.MEMBER_PROFILE_UPDATE;
            if ("/v1/operations/members/status-access".equals(path)) return EmployeeAccess.Capability.MEMBER_STATUS_UPDATE;
            if ("/v1/operations/directory/merchants/create-access".equals(path)) return EmployeeAccess.Capability.MERCHANT_CREATE;
            if ("/v1/operations/directory/stores/create-access".equals(path)) return EmployeeAccess.Capability.STORE_CREATE;
        }
        if ("/v1/admin/members".equals(path)) {
            if ("GET".equals(r.getMethod())) return EmployeeAccess.Capability.MEMBER_READ;
            if ("POST".equals(r.getMethod())) return EmployeeAccess.Capability.MEMBER_CREATE;
        }
        if ("GET".equals(r.getMethod()) && path.matches("/v1/admin/members/[A-Za-z0-9_-]{1,100}/history")) return EmployeeAccess.Capability.MEMBER_READ;
        if ("POST".equals(r.getMethod())) {
            if (path.matches("/v1/admin/members/[A-Za-z0-9_-]{1,100}/profile")) return EmployeeAccess.Capability.MEMBER_PROFILE_UPDATE;
            if (path.matches("/v1/admin/members/[A-Za-z0-9_-]{1,100}/status")) return EmployeeAccess.Capability.MEMBER_STATUS_UPDATE;
        }
        if ("GET".equals(r.getMethod()) && path.matches("/v1/admin/member-behavior/[A-Za-z0-9_-]{1,100}(/events)?")) return EmployeeAccess.Capability.MEMBER_BEHAVIOR_READ;
        if ("POST".equals(r.getMethod())) {
            if ("/v1/admin/member-behavior/rebuild".equals(path)) return EmployeeAccess.Capability.MEMBER_BEHAVIOR_REBUILD;
            if (path.matches("/v1/admin/member-behavior/[A-Za-z0-9_-]{1,100}/profile")) return EmployeeAccess.Capability.MEMBER_BEHAVIOR_UPDATE;
        }
        if ("/v1/admin/member-tags".equals(path)) {
            if ("GET".equals(r.getMethod())) return EmployeeAccess.Capability.MEMBER_TAG_READ;
            if ("POST".equals(r.getMethod())) return EmployeeAccess.Capability.MEMBER_TAG_DEFINE;
        }
        if ("GET".equals(r.getMethod()) && path.matches("/v1/admin/member-tags/[A-Za-z0-9_-]{1,100}/assignments")) return EmployeeAccess.Capability.MEMBER_TAG_READ;
        if ("POST".equals(r.getMethod()) && path.matches("/v1/admin/member-tags/[A-Za-z0-9_-]{1,100}/assign")) return EmployeeAccess.Capability.MEMBER_TAG_ASSIGN;
        if ("/v1/admin/member-cycles/policies".equals(path)) {
            if ("GET".equals(r.getMethod())) return EmployeeAccess.Capability.MEMBER_CYCLE_POLICY_READ;
            if ("POST".equals(r.getMethod())) return EmployeeAccess.Capability.MEMBER_CYCLE_POLICY_PUBLISH;
        }
        if ("GET".equals(r.getMethod()) && path.matches("/v1/admin/member-cycles/[A-Za-z0-9_-]{1,100}")) return EmployeeAccess.Capability.MEMBER_CYCLE_READ;
        if ("POST".equals(r.getMethod()) && path.matches("/v1/admin/member-cycles/[A-Za-z0-9_-]{1,100}/evaluate")) return EmployeeAccess.Capability.MEMBER_CYCLE_EVALUATE;
        if ("/v1/admin/member-cycle-benefits".equals(path)) {
            if ("GET".equals(r.getMethod())) return EmployeeAccess.Capability.CYCLE_BENEFIT_READ;
            if ("POST".equals(r.getMethod())) return EmployeeAccess.Capability.CYCLE_BENEFIT_DEFINE;
        }
        if ("POST".equals(r.getMethod()) && path.matches("/v1/admin/member-cycle-benefits/[A-Za-z0-9_-]{1,100}/grant")) return EmployeeAccess.Capability.CYCLE_BENEFIT_GRANT;
        if ("GET".equals(r.getMethod())) {
            if ("/v1/operations/member-points/policy-access".equals(path)) return EmployeeAccess.Capability.POINTS_POLICY_PUBLISH;
            if ("/v1/operations/member-points/adjust-access".equals(path)) return EmployeeAccess.Capability.POINTS_ADJUST;
            if ("/v1/operations/member-points/expire-access".equals(path)) return EmployeeAccess.Capability.POINTS_EXPIRE;
        }
        if ("/v1/admin/member-points/policies".equals(path)) {
            if ("GET".equals(r.getMethod())) return EmployeeAccess.Capability.POINTS_POLICY_READ;
            if ("POST".equals(r.getMethod())) return EmployeeAccess.Capability.POINTS_POLICY_PUBLISH;
        }
        if ("GET".equals(r.getMethod()) && path.matches("/v1/admin/member-points/[A-Za-z0-9_-]{1,100}(/ledger)?")) return EmployeeAccess.Capability.POINTS_READ;
        if ("POST".equals(r.getMethod())) {
            if (path.matches("/v1/admin/member-points/[A-Za-z0-9_-]{1,100}/adjust")) return EmployeeAccess.Capability.POINTS_ADJUST;
            if (path.matches("/v1/admin/member-points/[A-Za-z0-9_-]{1,100}/expire")) return EmployeeAccess.Capability.POINTS_EXPIRE;
        }
        if ("GET".equals(r.getMethod())) {
            if ("/v1/operations/point-offers/define-access".equals(path)) return EmployeeAccess.Capability.POINT_OFFER_DEFINE;
            if ("/v1/operations/point-offers/status-access".equals(path)) return EmployeeAccess.Capability.POINT_OFFER_STATUS_UPDATE;
        }
        if ("/v1/admin/point-offers".equals(path)) {
            if ("GET".equals(r.getMethod())) return EmployeeAccess.Capability.POINT_OFFER_READ;
            if ("POST".equals(r.getMethod())) return EmployeeAccess.Capability.POINT_OFFER_DEFINE;
        }
        if ("POST".equals(r.getMethod()) && path.matches("/v1/admin/point-offers/[A-Za-z0-9_-]{1,100}/status")) return EmployeeAccess.Capability.POINT_OFFER_STATUS_UPDATE;
        if ("/v1/admin/member-growth/policies".equals(path)) {
            if ("GET".equals(r.getMethod())) return EmployeeAccess.Capability.GROWTH_POLICY_READ;
            if ("POST".equals(r.getMethod())) return EmployeeAccess.Capability.GROWTH_POLICY_PUBLISH;
        }
        if ("GET".equals(r.getMethod()) && path.matches("/v1/admin/member-growth/[A-Za-z0-9_-]{1,100}(/ledger)?")) return EmployeeAccess.Capability.GROWTH_READ;
        if ("POST".equals(r.getMethod())) {
            if (path.matches("/v1/admin/member-growth/[A-Za-z0-9_-]{1,100}/adjust")) return EmployeeAccess.Capability.GROWTH_ADJUST;
            if (path.matches("/v1/admin/member-growth/[A-Za-z0-9_-]{1,100}/recalculate")) return EmployeeAccess.Capability.GROWTH_RECALCULATE;
        }
        return null;
    }
    private static final class EmployeeFilter extends OncePerRequestFilter {
        private final CentralEmployeeService service;
        EmployeeFilter(CentralEmployeeService service) { this.service = service; }
        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
            SecurityContextHolder.clearContext(); response.setHeader("Cache-Control", "no-store");
            try {
                String header = single(request, "Authorization"), tenant = single(request, "X-Tenant-Id");
                if (!header.startsWith("Bearer ")) throw new CentralAccessException(401);
                var actor = service.authenticate(header.substring(7), tenant, capability(request));
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of(new SimpleGrantedAuthority("CENTRAL_EMPLOYEE"))));
                SecurityContextHolder.setContext(context);
            } catch (CentralAccessException e) { CentralStoreConfiguration.error(response, e.status(), e.status() == org.springframework.http.HttpStatus.UNAUTHORIZED.value() ? "UNAUTHENTICATED" : "UNAVAILABLE"); return; }
            catch (AccessDeniedException e) { CentralStoreConfiguration.error(response, 403, "FORBIDDEN"); return; }
            catch (IllegalArgumentException e) { CentralStoreConfiguration.error(response, 400, "INVALID_ARGUMENT"); return; }
            catch (org.springframework.dao.DataAccessException e) { CentralStoreConfiguration.error(response, 503, "UNAVAILABLE"); return; }
            try { chain.doFilter(request, response); } finally { SecurityContextHolder.clearContext(); }
        }
        private static String single(HttpServletRequest request, String name) {
            var headers = request.getHeaders(name);
            if (headers == null || !headers.hasMoreElements()) throw new CentralAccessException(401);
            String value = headers.nextElement();
            if (headers.hasMoreElements() || value == null || value.isBlank()) throw new CentralAccessException(401);
            return value;
        }
    }
}
