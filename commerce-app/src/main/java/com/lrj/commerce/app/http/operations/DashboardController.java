package com.lrj.commerce.app.http.operations;

import com.lrj.commerce.catalog.assortment.api.CatalogApi;
import com.lrj.commerce.member.profile.api.MemberApi;
import com.lrj.commerce.insight.api.MarketingEffectsApi;
import com.lrj.commerce.runtime.api.identity.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;

/** 总览只组装各域聚合，不越过API读取其他域业务表。 */
@RestController
@RequestMapping("/v1/admin")
public class DashboardController {

	public record Totals(long paidOrders, String received, String refunded, String netReceipts,
			String discountGranted) {
	}

	public record View(String storeId, Instant from, Instant to, Instant generatedAt, MemberApi.Stats members,
			CatalogApi.Stats catalog, List<MarketingEffectsApi.Daily> daily, Totals totals, String coverage) {
	}

	private final MemberApi members;

	private final CatalogApi catalog;

	private final MarketingEffectsApi effects;

    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired private com.lrj.commerce.runtime.api.access.EmployeeAccess access;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<com.lrj.commerce.app.iam.CentralEmployeeService> central;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<com.lrj.commerce.app.iam.CentralScopeService> scope;


	public DashboardController(MemberApi members, CatalogApi catalog, MarketingEffectsApi effects, Clock clock) {
		this.members = members;
		this.catalog = catalog;
		this.effects = effects;
		this.clock = clock;
	}

	/** 今天及前29个UTC日，金额精确累加后输出，前端仅将数值用于图形高度。 */
	@GetMapping("/dashboard")
	public View read(@AuthenticationPrincipal Actor actor, @RequestParam String storeId,
            @RequestHeader(value="Authorization", required=false) String authorization,
            @RequestHeader(value="X-Tenant-Id", required=false) String tenant) {
        var permit=access.scope(actor,com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.DASHBOARD_READ);
        Actor member=actor,effect=actor;
        com.lrj.commerce.runtime.api.access.EmployeeAccess.ScopePermit memberPermit=null,effectPermit=null;
        com.lrj.commerce.app.iam.CentralStoreIdentity product=null;
        if(permit.identity()!=null) {
            if(authorization==null||!authorization.startsWith("Bearer ")||tenant==null)throw denied();
            var service=central.getIfAvailable();var scoped=scope.getIfAvailable();
            if(service==null||scoped==null)throw new com.lrj.commerce.kernel.DomainException(com.lrj.commerce.kernel.DomainException.Code.UNAVAILABLE,"仪表盘数据源授权不可用");
            String token=authorization.substring(7);
            member=service.authenticate(token,tenant,com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.MEMBER_READ);
            effect=service.authenticate(token,tenant,com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.MARKETING_EFFECT_READ);
            // 先核对每个独立来源的同一员工身份，后读取任何聚合，缺一项不会泄露其余总数。
            memberPermit=same(actor,permit,member,com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.MEMBER_READ);
            effectPermit=same(actor,permit,effect,com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.MARKETING_EFFECT_READ);
            product=scoped.authenticate(token,tenant,"product");
        }
		Instant now = clock.instant();
		LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate(), start = today.minusDays(29);
		Instant from = start.atStartOfDay(ZoneOffset.UTC).toInstant();
		var productProof=product==null?null:scope.getObject().prepareCatalogStats(product,actor,permit.identity(),storeId,catalog);
        var stats = productProof==null ? catalog.stats(actor,storeId) : productProof.value();
        var memberStats=members.stats(member);
		var source = effects.daily(effect, storeId, from, now);
		var byDay = new HashMap<LocalDate, MarketingEffectsApi.Daily>();
		source.forEach(d -> byDay.put(d.day(), d));
		var days = new ArrayList<MarketingEffectsApi.Daily>();
		long paid = 0;
		BigDecimal received = BigDecimal.ZERO, refunded = BigDecimal.ZERO, net = BigDecimal.ZERO,
				discount = BigDecimal.ZERO;
		for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
			var value = byDay.getOrDefault(day,
					new MarketingEffectsApi.Daily(day, 0, 0, "0.00", "0.00", "0.00", "0.00", "0.00", "0.00", null));
			days.add(value);
			paid = Math.addExact(paid, value.paidOrders());
			received = received.add(new BigDecimal(value.received()));
			refunded = refunded.add(new BigDecimal(value.refunded()));
			net = net.add(new BigDecimal(value.netReceipts()));
			discount = discount.add(new BigDecimal(value.discountGranted()));
		}
		// 后一个来源读取期间，前一个来源可能已撤权；聚合出口重验各原证明，不能只重验父能力。
        if(productProof!=null)productProof.verify().run();
        if(memberPermit!=null)com.lrj.commerce.runtime.api.access.EmployeeAccess.requireSame(memberPermit,access.scope(member,com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.MEMBER_READ));
        if(effectPermit!=null)com.lrj.commerce.runtime.api.access.EmployeeAccess.requireSame(effectPermit,access.scope(effect,com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.MARKETING_EFFECT_READ));
        com.lrj.commerce.runtime.api.access.EmployeeAccess.requireSame(permit,access.scope(actor,com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.DASHBOARD_READ));
        return new View(storeId, from, now, now, memberStats, stats, List.copyOf(days),
				new Totals(paid, amount(received), amount(refunded), amount(net), amount(discount)),
				"按UTC下单日期选择订单，扣除截至投影更新时已知的成功退款（含窗口外退款）。仅覆盖已消费事件或重建的订单，存在异步延迟；各域读取不构成同一数据库快照。");
	}

    /** 子读取引用分别绑定准确能力，主体/成员/代际不一致时查询前拒绝。 */
    private com.lrj.commerce.runtime.api.access.EmployeeAccess.ScopePermit same(Actor parent, com.lrj.commerce.runtime.api.access.EmployeeAccess.ScopePermit permit, Actor child,
            com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability capability) {
        var own=access.scope(child,capability);
        if(!parent.tenantId().equals(child.tenantId())||!parent.actorId().equals(child.actorId())||parent.role()!=child.role()
                ||!java.util.Objects.equals(permit.identity(),own.identity()))throw denied();
        return own;
    }
    private com.lrj.commerce.kernel.DomainException denied(){return new com.lrj.commerce.kernel.DomainException(com.lrj.commerce.kernel.DomainException.Code.FORBIDDEN,"仪表盘聚合来源身份不一致");}
	private String amount(BigDecimal value) {
		return value.setScale(2).toPlainString();
	}

    /** 聚合内独立签发仍是认证边界；缺子能力返回403，停服返回503，绝不误报业务500。 */
    @ExceptionHandler(com.lrj.authz.sdk.AccessDeniedException.class)
    public org.springframework.http.ResponseEntity<?> childDenied(com.lrj.authz.sdk.AccessDeniedException failure, jakarta.servlet.http.HttpServletRequest request) {
        return org.springframework.http.ResponseEntity.status(403).body(java.util.Map.of("code","FORBIDDEN","message","聚合来源或子业务操作未获授权","traceId",com.lrj.commerce.app.configuration.security.SecurityConfiguration.trace(request)));
    }
    @ExceptionHandler(com.lrj.authz.sdk.CentralAccessException.class)
    public org.springframework.http.ResponseEntity<?> childUnavailable(com.lrj.authz.sdk.CentralAccessException failure, jakarta.servlet.http.HttpServletRequest request) {
        int status=failure.status()==401?401:503;
        return org.springframework.http.ResponseEntity.status(status).body(java.util.Map.of("code",status==401?"UNAUTHENTICATED":"UNAVAILABLE","message",status==401?"登录已失效":"聚合来源授权暂不可用","traceId",com.lrj.commerce.app.configuration.security.SecurityConfiguration.trace(request)));
    }
}
