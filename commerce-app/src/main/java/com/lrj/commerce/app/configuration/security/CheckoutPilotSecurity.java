package com.lrj.commerce.app.configuration.security;

import com.lrj.commerce.kernel.Identifiers;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
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

/** 试点凭据只能从一个交易租户读取已配置门店的报价证据。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "commerce.checkout-pilot.enabled", havingValue = "true")
public class CheckoutPilotSecurity {

	public static final String PATH = "/internal/v1/checkout-pilot/quotes/{quoteId}";
	public static final String TRADE_TENANT_HEADER = "X-Trade-Tenant";
	private static final String BEARER_PREFIX = "Bearer ";
	private final Scope scope;
	private final byte[] bearerHash;

	public CheckoutPilotSecurity(@Value("${commerce.checkout-pilot.scope-version}") String scopeVersion,
			@Value("${commerce.checkout-pilot.trade-tenant}") String tradeTenant,
			@Value("${commerce.checkout-pilot.commerce-tenant}") String tenant,
			@Value("${commerce.checkout-pilot.merchant-id}") String merchant,
			@Value("${commerce.checkout-pilot.store-id}") String store,
			@Value("${commerce.checkout-pilot.sku-id}") String sku,
			@Value("${commerce.checkout-pilot.bearer-token}") String token) {
		this.scope = new Scope(Identifiers.require(scopeVersion), tradeTenant, Identifiers.require(tenant),
				Identifiers.require(merchant), Identifiers.require(store), Identifiers.require(sku));
		if (!tradeTenant.matches("[1-9][0-9]{0,18}") || !token.matches("[A-Za-z0-9_-]{43,128}")) {
			throw new IllegalArgumentException("商城试点需要固定交易租户和独立机器访问凭据");
		}
		Long.parseLong(tradeTenant);
		this.bearerHash = digest(token);
	}

	/** 范围使用启动时配置，防止用请求参数切换租户和门店。 */
	public record Scope(String version, String tradeTenantId, String commerceTenantId, String merchantId,
			String storeId, String skuId) {
	}

	/** 向读取用例提供已校验范围。 */
	@Bean
	Scope checkoutPilotScope() {
		return scope;
	}

	/** 机器凭据独立于会员和画像权限，仅允许读取明确的报价证据资源。 */
	@Bean
	@Order(-6)
	SecurityFilterChain checkoutPilotChain(HttpSecurity http) throws Exception {
		return http.securityMatcher(PATH)
			.sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(config -> config.requestMatchers(HttpMethod.GET, PATH)
				.access((authentication, context) -> new AuthorizationDecision(allowed(context.getRequest())))
				.anyRequest().denyAll())
			.exceptionHandling(config -> config.authenticationEntryPoint((request, response, error) ->
					SecurityConfiguration.error(response, 403, "FORBIDDEN", "商城试点机器授权无效",
							SecurityConfiguration.trace(request))))
			.build();
	}

	private boolean allowed(HttpServletRequest request) {
		var tenants = Collections.list(request.getHeaders(TRADE_TENANT_HEADER));
		var credentials = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
		if (!tenants.equals(java.util.List.of(scope.tradeTenantId())) || credentials.size() != 1) {
			return false;
		}
		String bearer = credentials.getFirst();
		return bearer.startsWith(BEARER_PREFIX) && bearer.length() <= 135
				&& MessageDigest.isEqual(bearerHash, digest(bearer.substring(BEARER_PREFIX.length())));
	}

	private static byte[] digest(String value) {
		return JsonCodec.hash(value).getBytes(StandardCharsets.US_ASCII);
	}
}
