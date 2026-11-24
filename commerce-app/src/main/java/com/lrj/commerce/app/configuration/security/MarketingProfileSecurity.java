package com.lrj.commerce.app.configuration.security;

import com.lrj.commerce.kernel.Identifiers;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** 画像凭据只授权一个读取端点和一个商城租户，用户凭据不能转用。 */
@Configuration
@ConditionalOnProperty(name = "commerce.marketing.member-profile.enabled", havingValue = "true")
public class MarketingProfileSecurity {

	public static final String PATH = "/internal/v1/member-profiles/snapshot";
	private static final String MARKETING_TENANT_HEADER = "X-Marketing-Tenant";
	private static final String BEARER_PREFIX = "Bearer ";
	private final String marketingTenant;
	private final String commerceTenant;
	private final byte[] bearerHash;

	public MarketingProfileSecurity(
			@Value("${commerce.marketing.member-profile.marketing-tenant}") String marketingTenant,
			@Value("${commerce.marketing.member-profile.commerce-tenant}") String commerceTenant,
			@Value("${commerce.marketing.member-profile.bearer-token}") String bearerToken) {
		this.marketingTenant = Identifiers.require(marketingTenant);
		this.commerceTenant = Identifiers.require(commerceTenant);
		if (!bearerToken.matches("[A-Za-z0-9_-]{43,128}")) {
			throw new IllegalArgumentException("商城画像需要独立的机器访问凭据");
		}
		this.bearerHash = digest(bearerToken);
	}

	/** 将调用方请求的租户与固定授权配对，不能从请求指定任意商城租户。 */
	@Bean
	@Order(-5)
	SecurityFilterChain marketingProfileChain(HttpSecurity http) throws Exception {
		return http.securityMatcher(PATH)
			.sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(c -> c.requestMatchers(HttpMethod.GET, PATH).access((authentication, context) -> {
				var request = context.getRequest();
				String header = request.getHeader(HttpHeaders.AUTHORIZATION);
				return new AuthorizationDecision(marketingTenant.equals(request.getHeader(MARKETING_TENANT_HEADER))
						&& header != null && header.startsWith(BEARER_PREFIX) && header.length() <= 135
						&& MessageDigest.isEqual(bearerHash, digest(header.substring(BEARER_PREFIX.length()))));
			}).anyRequest().denyAll())
			.exceptionHandling(c -> c.authenticationEntryPoint((req, res, error) ->
					SecurityConfiguration.error(res, 403, "FORBIDDEN", "商城画像机器授权无效",
							SecurityConfiguration.trace(req))))
			.build();
	}

	/** 端点只读取已绑定租户。 */
	public String commerceTenant() {
		return commerceTenant;
	}

	private static byte[] digest(String value) {
		return JsonCodec.hash(value).getBytes(StandardCharsets.US_ASCII);
	}

}
