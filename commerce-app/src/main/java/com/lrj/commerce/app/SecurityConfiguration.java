package com.lrj.commerce.app;
import com.lrj.commerce.runtime.JsonCodec;
import com.lrj.commerce.runtime.persistence.CredentialMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/** 仅接受显式Bearer凭据，无Cookie自动认证；外部IdP适配尚未启用。 */
@Configuration
public class SecurityConfiguration {
    @Bean SecurityFilterChain security(HttpSecurity http,CredentialMapper credentials) throws Exception {
        return http.csrf(c->c.disable()).sessionManagement(c->c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(c->c.requestMatchers("/actuator/health","/","/index.html","/assets/**").permitAll()
                .requestMatchers("/v1/admin/**").hasAuthority("ADMIN")
                .requestMatchers("/v1/me","/v1/runtime-capabilities").hasAnyAuthority("ADMIN","MEMBER","OPERATOR")
                .requestMatchers("/v1/operations/**").hasAnyAuthority("ADMIN","OPERATOR")
                .anyRequest().hasAnyAuthority("ADMIN","MEMBER"))
            .exceptionHandling(c->c.authenticationEntryPoint((req,res,error)->error(res,401,"UNAUTHENTICATED","需要有效访问凭据",trace(req)))
                .accessDeniedHandler((req,res,error)->error(res,403,"FORBIDDEN","没有操作权限",trace(req))))
            .addFilterBefore(new TokenFilter(credentials),AnonymousAuthenticationFilter.class).build();
    }
    static String trace(HttpServletRequest req) {return Objects.toString(req.getAttribute("traceId"),UUID.randomUUID().toString());}
    static void error(HttpServletResponse response,int status,String code,String message,String trace) throws IOException {
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JsonCodec.write(Map.of("code",code,"message",message,"traceId",trace)));
    }
    /** 有界读取请求体，chunked也不能绕过限制；不记录Bearer明文。 */
    private static final class TokenFilter extends OncePerRequestFilter {
        private final CredentialMapper credentials;
        TokenFilter(CredentialMapper credentials) {this.credentials=credentials;}
        protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
            String trace=UUID.randomUUID().toString();request.setAttribute("traceId",trace);response.setHeader("X-Trace-Id",trace);
            String authorization=request.getHeader("Authorization");
            if(authorization!=null) {
                if(!authorization.startsWith("Bearer ")||authorization.length()>519) {error(response,401,"UNAUTHENTICATED","访问凭据无效",trace);return;}
                com.lrj.commerce.runtime.api.Actor actor;
                try {actor=credentials.authenticate(JsonCodec.hash(authorization.substring(7)));}
                catch(org.springframework.dao.DataAccessException unavailable) {error(response,503,"UNAVAILABLE","身份校验暂不可用",trace);return;}
                if(actor==null) {error(response,401,"UNAUTHENTICATED","访问凭据无效",trace);return;}
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor,null,List.of(new SimpleGrantedAuthority(actor.role().name()))));
            }
            if(Set.of("POST","PUT","PATCH").contains(request.getMethod())) {
                byte[] bytes=request.getInputStream().readNBytes(65537);
                if(bytes.length>65536) {error(response,413,"LIMIT_EXCEEDED","请求体超过64KiB",trace);return;}
                var wrapped=new HttpServletRequestWrapper(request) {
                    @Override public ServletInputStream getInputStream() {
                        var input=new ByteArrayInputStream(bytes);
                        return new ServletInputStream() {
                            public int read() {return input.read();}
                            public boolean isFinished() {return input.available()==0;}
                            public boolean isReady() {return true;}
                            public void setReadListener(ReadListener listener) {throw new UnsupportedOperationException("异步请求体读取未启用");}
                        };
                    }
                    @Override public BufferedReader getReader() {return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
                };
                chain.doFilter(wrapped,response);return;
            }
            chain.doFilter(request,response);
        }
    }
}
