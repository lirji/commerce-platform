package com.lrj.commerce.app.configuration.security;

import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.runtime.identity.persistence.CredentialMapper;
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

/** 仅接受显式Bearer凭据，无Cookie自动认证；中央试点由更高优先级的独立过滤链处理。 */
@Configuration
public class SecurityConfiguration {

	/** 会员端显式放行清单；新增接口默认拒绝，必须在此登记，防止管理能力被默认暴露给会员。 */
	static final String[] MEMBER_PATHS = { "/v1/aftersales/**", "/v1/catalog/**", "/v1/coupon-definitions",
			"/v1/coupons/**", "/v1/entitlements/**", "/v1/journey-instances", "/v1/journey-instances/*/history",
			"/v1/members/me/**", "/v1/notifications",
			"/v1/orders/**", "/v1/point-offers/**", "/v1/point-redemptions", "/v1/quotes/**", "/v1/stores" };

	@Bean
	SecurityFilterChain security(HttpSecurity http, CredentialMapper credentials) throws Exception {
		return http.csrf(c -> c.disable())
			.sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(
					c -> c.requestMatchers("/actuator/health", "/", "/index.html", "/assets/**", "/media/**")
						.permitAll()
						.requestMatchers(org.springframework.http.HttpMethod.GET, "/operations/member-behavior", "/operations/member-tags", "/operations/member-growth", "/operations/members", "/operations/directory", "/operations/inventory", "/operations/catalog", "/operations/products", "/collaboration/products", "/iam/callback")
						.permitAll()
						.requestMatchers("/v1/admin/**")
						.hasAuthority("ADMIN")
						// 平台命名空间只对平台运维开放；租户管理员访问任何平台路径都是403，平台运维访问不存在的平台路径才是404。
						.requestMatchers("/v1/platform/**")
						.hasAuthority("PLATFORM_OPERATOR")
						.requestMatchers("/v1/me", "/v1/runtime-capabilities")
						.hasAnyAuthority("ADMIN", "MEMBER", "OPERATOR")
						.requestMatchers("/v1/operations/**")
						.hasAnyAuthority("ADMIN", "OPERATOR")
						.requestMatchers(MEMBER_PATHS)
						.hasAnyAuthority("ADMIN", "MEMBER")
						.anyRequest()
						.denyAll())
			.exceptionHandling(c -> c
				.authenticationEntryPoint(
						(req, res, error) -> error(res, 401, "UNAUTHENTICATED", "需要有效访问凭据", trace(req)))
				.accessDeniedHandler((req, res, error) -> error(res, 403, "FORBIDDEN", "没有操作权限", trace(req))))
			.addFilterBefore(new TokenFilter(credentials), AnonymousAuthenticationFilter.class)
			.build();
	}

	/** 统一读取请求追踪标识，供鉴权和 HTTP 错误边界复用。 */
	public static String trace(HttpServletRequest req) {
		return Objects.toString(req.getAttribute("traceId"), UUID.randomUUID().toString());
	}

	static void error(HttpServletResponse response, int status, String code, String message, String trace)
			throws IOException {
		response.setStatus(status);
		response.setContentType("application/json;charset=UTF-8");
		response.getWriter().write(JsonCodec.write(Map.of("code", code, "message", message, "traceId", trace)));
	}

	/** 有界读取请求体，chunked也不能绕过限制；不记录Bearer明文。 */
	private static final class TokenFilter extends OncePerRequestFilter {

		private final CredentialMapper credentials;

		TokenFilter(CredentialMapper credentials) {
			this.credentials = credentials;
		}

		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			String trace = UUID.randomUUID().toString();
			request.setAttribute("traceId", trace);
			response.setHeader("X-Trace-Id", trace);
			String authorization = request.getHeader("Authorization");
			if (authorization != null) {
				if (!authorization.startsWith("Bearer ") || authorization.length() > 519) {
					error(response, 401, "UNAUTHENTICATED", "访问凭据无效", trace);
					return;
				}
				com.lrj.commerce.runtime.api.identity.Actor actor;
				try {
					actor = credentials.authenticate(JsonCodec.hash(authorization.substring(7)));
				}
				catch (org.springframework.dao.DataAccessException unavailable) {
					error(response, 503, "UNAVAILABLE", "身份校验暂不可用", trace);
					return;
				}
				if (actor == null) {
					error(response, 401, "UNAUTHENTICATED", "访问凭据无效", trace);
					return;
				}
				var authorities = new ArrayList<SimpleGrantedAuthority>();
				authorities.add(new SimpleGrantedAuthority(actor.role().name()));
				for (var capability : actor.capabilities())
					authorities.add(new SimpleGrantedAuthority(capability.name()));
				SecurityContextHolder.getContext()
					.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, null, authorities));
			}
			if (Set.of("POST", "PUT", "PATCH").contains(request.getMethod())) {
				byte[] bytes = request.getInputStream().readNBytes(65537);
				if (bytes.length > 65536) {
					error(response, 413, "LIMIT_EXCEEDED", "请求体超过64KiB", trace);
					return;
				}
				var wrapped = new HttpServletRequestWrapper(request) {
					@Override
					public ServletInputStream getInputStream() {
						var input = new ByteArrayInputStream(bytes);
						return new ServletInputStream() {
							public int read() {
								return input.read();
							}

							public boolean isFinished() {
								return input.available() == 0;
							}

							public boolean isReady() {
								return true;
							}

							public void setReadListener(ReadListener listener) {
								throw new UnsupportedOperationException("异步请求体读取未启用");
							}
						};
					}

					@Override
					public BufferedReader getReader() {
						return new BufferedReader(
								new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
					}
				};
				chain.doFilter(wrapped, response);
				return;
			}
			chain.doFilter(request, response);
		}

	}

}
