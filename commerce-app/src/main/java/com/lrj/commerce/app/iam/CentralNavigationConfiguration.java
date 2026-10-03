package com.lrj.commerce.app.iam;

import com.lrj.authz.sdk.*;
import com.lrj.authz.sdk.AccessDeniedException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/** 独立只读中央链，旧商城Token、管理Token或旧服务失败都没有OR回退。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"commerce.iam.store-read.enabled","commerce.iam.navigation.enabled"},havingValue="true")
public class CentralNavigationConfiguration {
    /** 本地映射属于Commerce，Auth不直接访问商城库。 */
    @Bean CentralNavigationService centralNavigation(CentralAccessClient client,CentralStoreBindingMapper bindings) {
        return new CentralNavigationService(client,bindings);
    }
    /** 只允许准确GET路径，新增业务接口不会继承导航authority。 */
    @Bean @Order(-10)
    SecurityFilterChain centralNavigationSecurity(HttpSecurity http,CentralNavigationService service) throws Exception {
        return http.securityMatcher("/v1/operations/navigation").csrf(c->c.disable())
                .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new NavigationFilter(service),AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(c->c.requestMatchers(HttpMethod.GET,"/v1/operations/navigation").hasAuthority("CENTRAL_NAVIGATION").anyRequest().denyAll())
                .exceptionHandling(c->c.authenticationEntryPoint((req,res,e)->CentralStoreConfiguration.error(res,401,"UNAUTHENTICATED"))
                        .accessDeniedHandler((req,res,e)->CentralStoreConfiguration.error(res,403,"FORBIDDEN"))).build();
    }
    /** 安全上下文只保存本次已验证响应，不保留Bearer或授予任何业务authority。 */
    static final class NavigationFilter extends OncePerRequestFilter {
        private final CentralNavigationService service;
        NavigationFilter(CentralNavigationService service){this.service=service;}
        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
            SecurityContextHolder.clearContext();response.setHeader("Cache-Control","no-store");
            try {
                if(!"GET".equals(request.getMethod())){CentralStoreConfiguration.error(response,403,"FORBIDDEN");return;}
                String header=single(request,"Authorization"),tenant=single(request,"X-Tenant-Id");
                if(!header.startsWith("Bearer "))throw new CentralAccessException(401);
                if(request.getParameterMap().keySet().stream().anyMatch(key->!"expected_membership_generation".equals(key)))throw new IllegalArgumentException("invalid navigation query");
                String[] values=request.getParameterValues("expected_membership_generation");Long generation=null;
                if(values!=null) {
                    if(values.length!=1||!values[0].matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException("invalid navigation generation");
                    generation=Long.valueOf(values[0]);
                }
                var view=service.current(header.substring(7),tenant,generation);
                var context=SecurityContextHolder.createEmptyContext();
                context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(view,null,List.of(new SimpleGrantedAuthority("CENTRAL_NAVIGATION"))));
                SecurityContextHolder.setContext(context);
                chain.doFilter(request,response);
            } catch(CentralAccessException failure){CentralStoreConfiguration.error(response,failure.status(),failure.status()==org.springframework.http.HttpStatus.UNAUTHORIZED.value()?"UNAUTHENTICATED":"UNAVAILABLE");}
            catch(AccessDeniedException failure){CentralStoreConfiguration.error(response,403,"FORBIDDEN");}
            catch(IllegalArgumentException failure){CentralStoreConfiguration.error(response,400,"INVALID_ARGUMENT");}
            catch(org.springframework.dao.DataAccessException failure){CentralStoreConfiguration.error(response,503,"UNAVAILABLE");}
            finally{SecurityContextHolder.clearContext();}
        }
        private static String single(HttpServletRequest request,String name) {
            var headers=request.getHeaders(name);
            if(headers==null||!headers.hasMoreElements())throw new CentralAccessException(401);
            String value=headers.nextElement();if(headers.hasMoreElements()||value==null||value.isBlank())throw new CentralAccessException(401);return value;
        }
    }
}
