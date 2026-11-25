package com.lrj.commerce.app.application.checkout;

import com.lrj.commerce.app.configuration.security.CheckoutPilotSecurity.Scope;
import com.lrj.commerce.catalog.assortment.api.CatalogApi;
import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.kernel.Money;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.store.management.api.StoreApi;
import com.lrj.commerce.trade.api.QuoteApi;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 按已持久化的商城报价核验门店与商品，避免把客户端传入的店铺当作交易证据。 */
@Service
@ConditionalOnProperty(name = "commerce.checkout-pilot.enabled", havingValue = "true")
public class CheckoutPilotQuoteService {
	private static final Pattern QUOTE_ID = Pattern.compile("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}");
	private static final String READER = "checkout-pilot-reader";
	private static final String CNY = "CNY";
	private static final String OWNER_REF_NAMESPACE = "commerce-checkout-owner-v1";
	private final Scope scope;
	private final QuoteApi quotes;
	private final StoreApi stores;
	private final CatalogApi catalog;
	private final Clock clock;

	public CheckoutPilotQuoteService(Scope scope, QuoteApi quotes, StoreApi stores, CatalogApi catalog, Clock clock) {
		this.scope = scope;
		this.quotes = quotes;
		this.stores = stores;
		this.catalog = catalog;
		this.clock = clock;
	}

	/** 在同一读视图复核报价、门店及当前 WEB 商品修订；下游用报价标识关联当前结算。 */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public Evidence read(String quoteId) {
		if (!QUOTE_ID.matcher(quoteId).matches()) {
			throw new DomainException(DomainException.Code.INVALID_INPUT, "报价标识格式无效");
		}
		var quote = quotes.internalBatch(scope.commerceTenantId(), List.of(quoteId)).getFirst();
		if (!scope.storeId().equals(quote.storeId()) || !scope.merchantId().equals(quote.merchantId())
				|| quote.channel() != Actor.Channel.WEB
				|| quote.items().stream().noneMatch(item -> scope.skuId().equals(item.skuId()))) {
			throw new DomainException(DomainException.Code.NOT_FOUND, "商城报价不在试点范围");
		}
		if (!CNY.equals(quote.currency()) || new BigDecimal(quote.discount()).signum() != 0
				|| quote.coupon() != null || quote.points() != null) {
			throw new DomainException(DomainException.Code.CONFLICT, "试点需要人民币且未应用其他优惠的报价");
		}
		var now = clock.instant();
		requireCurrent(quote.expiresAt(), now);
		var reader = new Actor(scope.commerceTenantId(), READER, Actor.Role.MEMBER, Actor.Channel.WEB);
		var store = stores.requireActive(reader, quote.storeId());
		if (!scope.merchantId().equals(store.merchantId())) {
			throw new DomainException(DomainException.Code.NOT_FOUND, "商城报价不在试点范围");
		}
		var activePrices = catalog.priced(reader, store.storeId(), quote.items().stream().map(QuoteApi.Line::skuId).toList(), now)
			.stream().collect(Collectors.toMap(CatalogApi.Price::skuId, Function.identity()));
		var items = quote.items().stream().map(item -> line(item, activePrices.get(item.skuId()))).toList();
		requireCurrent(quote.expiresAt(), clock.instant());
		// 将主体标识收敛到本报价，下游只能与同一次可信结算的主体引用比较。
		String ownerRef = JsonCodec.hash(JsonCodec.write(List.of(OWNER_REF_NAMESPACE, scope.commerceTenantId(),
				quote.memberId(), quoteId)));
		return new Evidence(scope.version(), scope.tradeTenantId(), scope.commerceTenantId(), store.merchantId(),
				store.storeId(), store.version(), scope.skuId(), quoteId, ownerRef, quote.currency(), quote.expiresAt(), items);
	}

	private static Line line(QuoteApi.Line item, CatalogApi.Price price) {
		var quoted = new BigDecimal(item.unitPrice());
		if (price == null || item.revision() != price.revision()
				|| item.channelPriceVersion() != price.channelPriceVersion()
				|| quoted.compareTo(new BigDecimal(price.unitPrice())) != 0) {
			throw new DomainException(DomainException.Code.CONFLICT, "商品修订或价格已变化，请重新报价");
		}
		return new Line(item.skuId(), item.revision(), item.channelPriceVersion(), item.quantity(),
				Long.toString(new Money(quoted).minorUnits()));
	}

	private static void requireCurrent(Instant expiresAt, Instant now) {
		if (!now.isBefore(expiresAt)) {
			throw new DomainException(DomainException.Code.CONFLICT, "商城报价已过期");
		}
	}

	/** 只暴露结算核验必要字段，价格使用十进制分值字符串。 */
	public record Line(String skuId, long skuRevision, long channelPriceVersion, int quantity, String unitPriceMinor) {
	}

	/** 原始商城报价关联到固定交易租户和范围版本。 */
	public record Evidence(String scopeVersion, String tradeTenantId, String commerceTenantId, String merchantId,
			String storeId, long storeVersion, String eligibleSkuId, String commerceQuoteId, String ownerRef,
			String currency, Instant expiresAt, List<Line> items) {
		public Evidence {
			items = List.copyOf(items);
		}
	}
}
