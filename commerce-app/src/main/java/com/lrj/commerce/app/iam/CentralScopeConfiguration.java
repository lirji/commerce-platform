package com.lrj.commerce.app.iam;

import com.lrj.authz.sdk.*;
import com.lrj.authz.sdk.AccessDeniedException;
import com.lrj.commerce.runtime.api.scope.ScopeQuery;
import com.lrj.commerce.runtime.command.Commands;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
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

/** P3试点独立默认关闭；启用后没有旧管理员或本地Grant的OR回退。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"commerce.iam.store-read.enabled","commerce.iam.scope.enabled"},havingValue="true")
public class CentralScopeConfiguration {
    /** 复用P2私密服务配置及显式身份桥，范围查询留在资源Owner模块。 */
    @Bean CentralScopeService centralScopeService(CentralAccessClient client,CentralStoreBindingMapper bindings,ScopeWorkMapper work,Commands commands,List<ScopeQuery> owners){return new CentralScopeService(client,bindings,work,commands,owners);}
    /** 只开放本片固定端点，身份在过滤器和应用用例两层检查。 */
    @Bean @Order(-1)
    SecurityFilterChain centralScopeSecurity(HttpSecurity http,CentralScopeService service)throws Exception{
        return http.securityMatcher("/v1/operations/scoped/**").csrf(c->c.disable())
            .sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new ScopeFilter(service),AnonymousAuthenticationFilter.class)
            .authorizeHttpRequests(c->c.anyRequest().hasAuthority("CENTRAL_SCOPE"))
            .exceptionHandling(c->c.authenticationEntryPoint((req,res,e)->CentralStoreConfiguration.error(res,401,"UNAUTHENTICATED"))
                .accessDeniedHandler((req,res,e)->CentralStoreConfiguration.error(res,403,"FORBIDDEN"))).build();
    }
    /** 凭据只活在当前请求；不能将身份上下文保存进session或导出任务。 */
    private static final class ScopeFilter extends OncePerRequestFilter {
        private final CentralScopeService service;
        ScopeFilter(CentralScopeService service){this.service=service;}
        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
            SecurityContextHolder.clearContext();
            response.setHeader("Cache-Control","no-store");
            try{
                String authorization=single(request,"Authorization"),tenant=single(request,"X-Tenant-Id");
                if(!authorization.startsWith("Bearer "))throw new CentralAccessException(401);
                String[] path=request.getRequestURI().substring(request.getContextPath().length()).split("/",-1);
                if(path.length<5)throw new IllegalArgumentException("缺少资源类型");
                var identity=service.authenticate(authorization.substring(7),tenant,path[4]);
                var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(identity,null,List.of(new SimpleGrantedAuthority("CENTRAL_SCOPE"))));SecurityContextHolder.setContext(context);
            }catch(CentralAccessException e){CentralStoreConfiguration.error(response,e.status(),e.status()==HttpStatus.UNAUTHORIZED.value()?"UNAUTHENTICATED":"UNAVAILABLE");return;}
            catch(AccessDeniedException e){CentralStoreConfiguration.error(response,403,"FORBIDDEN");return;}
            catch(IllegalArgumentException e){CentralStoreConfiguration.error(response,400,"INVALID_ARGUMENT");return;}
            catch(org.springframework.dao.DataAccessException e){CentralStoreConfiguration.error(response,503,"UNAVAILABLE");return;}
            try{chain.doFilter(request,response);}finally{SecurityContextHolder.clearContext();}
        }
        private String single(HttpServletRequest request,String name){var values=request.getHeaders(name);if(values==null||!values.hasMoreElements())throw new CentralAccessException(401);String value=values.nextElement();if(values.hasMoreElements()||value==null||value.isBlank())throw new CentralAccessException(401);return value;}
    }
}
