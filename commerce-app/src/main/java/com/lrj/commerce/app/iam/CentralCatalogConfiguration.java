package com.lrj.commerce.app.iam;

import com.lrj.authz.sdk.*;
import com.lrj.authz.sdk.AccessDeniedException;
import com.lrj.commerce.store.access.api.CatalogAuthorityRoutes;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.catalog.job.api.CatalogJobApi;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.time.Instant;
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

/** 完整经营入口只在明确中央租户选择下启用；旧入口仍由用例路由拦截，不能通过换header回退。 */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"commerce.iam.store-read.enabled","commerce.iam.catalog.enabled"},havingValue="true")
public class CentralCatalogConfiguration {
    /** 显式装配端口，不把SDK引入store或catalog领域。 */
    @Bean CentralCatalogService centralCatalogService(CentralAccessClient client,CentralStoreBindingMapper bindings,CatalogAuthorityRoutes routes){return new CentralCatalogService(client,bindings,routes);}
    /** X-Tenant-Id只选择认证链，真正租户仍来自中央身份映射与持久路由。 */
    @Bean @Order(-2) SecurityFilterChain centralCatalogSecurity(HttpSecurity http,CentralCatalogService service)throws Exception {
        return http.securityMatcher(request->catalogPath(request.getRequestURI().substring(request.getContextPath().length()))&&request.getHeader("X-Tenant-Id")!=null)
            .csrf(c->c.disable()).sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new CatalogFilter(service),AnonymousAuthenticationFilter.class)
            .authorizeHttpRequests(c->c.anyRequest().hasAuthority("CENTRAL_CATALOG"))
            .exceptionHandling(c->c.authenticationEntryPoint((req,res,e)->CentralStoreConfiguration.error(res,401,"UNAUTHENTICATED"))
                .accessDeniedHandler((req,res,e)->CentralStoreConfiguration.error(res,403,"FORBIDDEN"))).build();
    }
    private static boolean catalogPath(String path){return path.matches("/v1/operations/(products|skus|catalog-jobs|catalog-categories|specification-templates|catalog-search)(/.*)?");}
    /** 有界解析只为任务截止时间；原字节继续交由正式Controller解析，不信任请求体中的身份。 */
    private static final class CatalogFilter extends OncePerRequestFilter {
        private final CentralCatalogService service;
        CatalogFilter(CentralCatalogService service){this.service=service;}
        @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
            SecurityContextHolder.clearContext();response.setHeader("Cache-Control","no-store");
            HttpServletRequest input=request;
            try {
                String header=single(request,"Authorization"),tenant=single(request,"X-Tenant-Id");
                if(!header.startsWith("Bearer "))throw new CentralAccessException(401);
                Instant until=Instant.now().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
                if(Set.of("POST","PUT","PATCH").contains(request.getMethod())) {
                    byte[] bytes=request.getInputStream().readNBytes(65537);
                    if(bytes.length>65536){CentralStoreConfiguration.error(response,413,"LIMIT_EXCEEDED");return;}
                    input=wrap(request,bytes);
                    if("POST".equals(request.getMethod())&&request.getRequestURI().equals(request.getContextPath()+"/v1/operations/catalog-jobs")) {
                        try { var job=JsonCodec.read(new String(bytes,java.nio.charset.StandardCharsets.UTF_8),CatalogJobApi.Create.class);until=job.deadline(); }
                        catch(RuntimeException e){throw new IllegalArgumentException("任务格式无效");}
                        if(until==null||!until.isAfter(Instant.now())||until.isAfter(Instant.now().plusSeconds(37*86400L+60)))throw new IllegalArgumentException("任务期限无效");
                    }
                }
                var actor=service.authenticate(header.substring(7),tenant,until);
                var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor,null,List.of(new SimpleGrantedAuthority("CENTRAL_CATALOG"))));SecurityContextHolder.setContext(context);
            }catch(CentralAccessException e){CentralStoreConfiguration.error(response,e.status(),e.status()==org.springframework.http.HttpStatus.UNAUTHORIZED.value()?"UNAUTHENTICATED":"UNAVAILABLE");return;}
            catch(AccessDeniedException e){CentralStoreConfiguration.error(response,403,"FORBIDDEN");return;}
            catch(IllegalArgumentException e){CentralStoreConfiguration.error(response,400,"INVALID_ARGUMENT");return;}
            catch(org.springframework.dao.DataAccessException e){CentralStoreConfiguration.error(response,503,"UNAVAILABLE");return;}
            try{chain.doFilter(input,response);}finally{SecurityContextHolder.clearContext();}
        }
        private static String single(HttpServletRequest request,String name){var h=request.getHeaders(name);if(h==null||!h.hasMoreElements())throw new CentralAccessException(401);String value=h.nextElement();if(h.hasMoreElements()||value==null||value.isBlank())throw new CentralAccessException(401);return value;}
        private static HttpServletRequest wrap(HttpServletRequest request,byte[] bytes){
            return new HttpServletRequestWrapper(request){
                @Override public ServletInputStream getInputStream(){var input=new ByteArrayInputStream(bytes);return new ServletInputStream(){
                    public int read(){return input.read();}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}
                    public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("异步请求体读取未启用");}
                };}
                @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
            };
        }
    }
}
