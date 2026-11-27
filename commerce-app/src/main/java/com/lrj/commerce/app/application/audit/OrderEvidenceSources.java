package com.lrj.commerce.app.application.audit;

import com.lrj.commerce.app.application.audit.OrderOperationEvidence.Marketing;
import com.lrj.commerce.app.application.audit.OrderOperationEvidence.Result;
import com.lrj.commerce.app.application.audit.OrderOperationEvidence.Trade;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** 同单查询只访问配置中固定的服务端来源，凭据由现有密钥交付注入。 */
@Component
@ConditionalOnProperty(name = {"commerce.checkout-pilot.enabled", "commerce.order-audit.enabled"}, havingValue = "true")
public class OrderEvidenceSources {
    private final RestClient trade;
    private final RestClient marketing;

    public OrderEvidenceSources(@Value("${commerce.order-audit.trade-url}") URI tradeUrl,
            @Value("${commerce.order-audit.trade-token}") String tradeToken,
            @Value("${commerce.order-audit.funding-url}") URI marketingUrl,
            @Value("${commerce.order-audit.funding-token}") String marketingToken) {
        trade = client(tradeUrl, tradeToken);
        marketing = client(marketingUrl, marketingToken);
    }

    /** 交易查真实收退并返回下一个原生游标。 */
    Result<Trade> trade(String order, String receiptAfter, String refundAfter) {
        return read(() -> trade.get().uri(builder -> builder.path("/api/v1/trade/admin/orders/{order}/operation-evidence")
                .queryParamIfPresent("receiptAfter", Optional.ofNullable(receiptAfter))
                .queryParamIfPresent("refundAfter", Optional.ofNullable(refundAfter)).build(order)).retrieve().body(Trade.class));
    }

    /** 资金服务用原生订单关联与自己的范围进行最终判定。 */
    Result<Marketing> marketing(String tenant, String order, String cursor) {
        return read(() -> marketing.get().uri(builder -> builder.path("/api/v1/funding/trade-orders/{tenant}/{order}/operation-audits")
                .queryParamIfPresent("cursor", Optional.ofNullable(cursor)).build(tenant, order)).retrieve().body(Marketing.class));
    }

    private static <T> Result<T> read(Supplier<T> call) {
        try {
            T value = call.get();
            return value == null ? Result.unavailable() : Result.available(value);
        } catch (RestClientResponseException failure) {
            return failure.getStatusCode().value() == 404 ? Result.unlinked() : Result.unavailable();
        } catch (RestClientException unavailable) {
            return Result.unavailable();
        }
    }

    private static RestClient client(URI base, String credential) {
        boolean local = "localhost".equals(base.getHost()) || "127.0.0.1".equals(base.getHost());
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !base.getPath().matches("/?") || !("https".equals(base.getScheme()) || local && "http".equals(base.getScheme()))
                || credential.isBlank() || credential.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("订单证据来源需要固定 HTTPS 地址及独立凭据");
        }
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofMillis(1100));
        return RestClient.builder().baseUrl(base.toString()).requestFactory(factory)
                .defaultHeaders(headers -> headers.setBearerAuth(credential)).build();
    }
}
