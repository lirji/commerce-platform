package com.lrj.commerce.app.application.audit;

import com.lrj.commerce.app.configuration.security.CheckoutPilotSecurity.Scope;
import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability;
import com.lrj.commerce.runtime.api.access.EmployeeAccess.Permit;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.audit.OperationAudit;
import com.lrj.commerce.store.management.api.StoreApi;
import com.lrj.commerce.trade.api.QuoteApi;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 在已授权试点店查询三方证据，各源只读且分别报告可用性。 */
@Service
@ConditionalOnProperty(name = {"commerce.checkout-pilot.enabled", "commerce.order-audit.enabled"}, havingValue = "true")
public class OrderOperationEvidence {
    private static final List<Capability> READ = List.of(Capability.ORDER_READ, Capability.PAYMENT_READ, Capability.REFUND_READ);
    private final Scope scope;
    private final StoreApi stores;
    private final QuoteApi quotes;
    private final EmployeeAccess access;
    private final OperationAudit audit;
    private final OrderEvidenceSources sources;
    private final Semaphore requests = new Semaphore(8);

    public OrderOperationEvidence(Scope scope, StoreApi stores, QuoteApi quotes, EmployeeAccess access,
            OperationAudit audit, OrderEvidenceSources sources) {
        this.scope = scope;
        this.stores = stores;
        this.quotes = quotes;
        this.access = access;
        this.audit = audit;
        this.sources = sources;
    }

    /** 先授权真实门店再出网。返回前复核权限，避免等待下游时撤权仍泄露。 */
    public Page read(Actor actor, String orderNo, Source source, String cursor, String traceId) {
        if (!orderNo.matches("TRD-ORD-[0-9]{6}-[0-9]{10}")
                || cursor != null && !cursor.matches("[1-9][0-9]{0,18}")) {
            throw new DomainException(DomainException.Code.INVALID_INPUT, "请输入有效交易订单号和结果游标");
        }
        var grant = authorized(actor);
        return audit.orderEvidenceRead(actor, scope.storeId(), traceId, () -> {
            if (!requests.tryAcquire()) throw new DomainException(DomainException.Code.UNAVAILABLE, "同单证据查询繁忙");
            try {
                var marketing = marketingPermit(actor, source);
                Page page = load(orderNo, source, cursor, marketing != null);
                var current = authorized(actor);
                for (int i = 0; i < grant.size(); i++) {
                    var a = grant.get(i);
                    var b = current.get(i);
                    if (!Objects.equals(a.route(), b.route()) || !Objects.equals(a.identity(), b.identity())
                            || !a.fact().equals(b.fact())) throw forbidden();
                }
                if (marketing != null) EmployeeAccess.requireSame(marketing, access.scope(actor, Capability.MARKETING_EXECUTION_READ));
                return page;
            } finally {
                requests.release();
            }
        });
    }

    private List<Permit> authorized(Actor actor) {
        if (!scope.commerceTenantId().equals(actor.tenantId())) throw forbidden();
        var store = stores.fact(actor, scope.storeId());
        if (!scope.merchantId().equals(store.merchantId())) throw forbidden();
        var fact = new EmployeeAccess.StoreFact(store.storeId(), store.version());
        return READ.stream().map(capability -> access.require(actor, capability, fact)).toList();
    }

    private EmployeeAccess.ScopePermit marketingPermit(Actor actor, Source source) {
        if (source != Source.ALL && source != Source.MARKETING) return null;
        try {
            return access.scope(actor, Capability.MARKETING_EXECUTION_READ);
        } catch (DomainException cause) {
            if (cause.code() != DomainException.Code.FORBIDDEN) throw cause;
            return null;
        }
    }

    private Page load(String orderNo, Source source, String cursor, boolean mayReadMarketing) {
        var trade = sources.trade(orderNo, source == Source.RECEIPTS ? cursor : null,
                source == Source.REFUNDS ? cursor : null);
        if (trade.status() != Status.AVAILABLE) {
            return new Page(orderNo, Result.unlinked(), trade, Result.unlinked());
        }
        var value = trade.data();
        if (!scope.tradeTenantId().equals(value.tradeTenant()) || !orderNo.equals(value.businessNo())
                || !trustedBinding(value.binding())) {
            throw new DomainException(DomainException.Code.NOT_FOUND, "当前门店没有可核验的原生订单关联");
        }
        var commerce = quote(value.binding());
        if (commerce.status() == Status.NOT_LINKED) {
            throw new DomainException(DomainException.Code.NOT_FOUND, "当前门店没有可核验的商城报价");
        }
        var marketing = Result.<Marketing>unlinked();
        if (source == Source.ALL || source == Source.MARKETING) {
            if (!mayReadMarketing) {
                marketing = new Result<>(Status.RESTRICTED, null);
            } else if (value.binding().fundingApplicationId() != null) {
                marketing = sources.marketing(value.tradeTenant(), value.orderId(), source == Source.MARKETING ? cursor : null);
                if (marketing.status() == Status.AVAILABLE && (!value.binding().checkoutReference().equals(marketing.data().checkoutReference())
                        || !value.binding().fundingApplicationId().equals(marketing.data().applicationId()))) marketing = Result.unlinked();
            }
        }
        return new Page(orderNo, commerce, trade, marketing);
    }

    private boolean trustedBinding(Binding binding) {
        return binding != null && scope.commerceTenantId().equals(binding.commerceTenant())
                && scope.merchantId().equals(binding.merchantId()) && scope.storeId().equals(binding.storeId())
                && scope.version().equals(binding.scopeVersion())
                && binding.commerceQuoteId() != null && binding.checkoutReference() != null;
    }

    private Result<Commerce> quote(Binding binding) {
        try {
            var quote = quotes.internalBatch(scope.commerceTenantId(), List.of(binding.commerceQuoteId())).stream().findFirst().orElse(null);
            if (quote == null || !scope.storeId().equals(quote.storeId()) || !scope.merchantId().equals(quote.merchantId())) return Result.unlinked();
            return Result.available(new Commerce(quote.businessNo(), quote.storeId(), quote.createdAt()));
        } catch (DomainException missing) {
            if (missing.code() == DomainException.Code.NOT_FOUND) return Result.unlinked();
            throw missing;
        }
    }

    private static DomainException forbidden() {
        return new DomainException(DomainException.Code.FORBIDDEN, "当前人员未获试点订单证据权限");
    }

    public enum Source { ALL, RECEIPTS, REFUNDS, MARKETING }

    public enum Status { AVAILABLE, UNAVAILABLE, NOT_LINKED, RESTRICTED }

    public record Result<T>(Status status, T data) {
        static <T> Result<T> available(T value) { return new Result<>(Status.AVAILABLE, value); }
        static <T> Result<T> unlinked() { return new Result<>(Status.NOT_LINKED, null); }
        static <T> Result<T> unavailable() { return new Result<>(Status.UNAVAILABLE, null); }
    }

    public record Page(String requestedOrderNo, Result<Commerce> commerce, Result<Trade> trade, Result<Marketing> marketing) {}

    public record Commerce(String businessNo, String storeId, Instant createdAt) {}

    public record Binding(String commerceTenant, String merchantId, String storeId, String commerceQuoteId,
            String scopeVersion, String checkoutReference, String fundingApplicationId) {}

    public record Trade(String tradeTenant, String orderId, String businessNo, String status, String financialStatus,
            String currency, Instant createdAt, Instant paidAt, Binding binding, List<Receipt> receipts,
            String receiptNextAfter, List<Refund> refunds, String refundNextAfter) {}

    public record Receipt(String businessNo, String amountMinor, String currency, String disposition, Instant confirmedAt) {}

    public record Refund(String businessNo, String amountMinor, String currency, String status, Instant createdAt) {}

    public record Marketing(String checkoutReference, String orderNo, String applicationId, String applicationNo,
            String campaignId, long generation, FundingHistory history) {}

    public record FundingHistory(List<FundingEffect> items, String nextCursor) {}

    public record FundingEffect(String businessNo, String action, String result, String amountMinor, String currency,
            String beforeState, String afterState, Instant occurredAt) {}
}
