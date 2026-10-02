package com.lrj.commerce.app.http.operations;

import com.lrj.commerce.ops.api.OpsPageApi;
import com.lrj.commerce.runtime.api.identity.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 低代码入口仍执行真实业务权限，预览与动作端点分离。 */
@RestController
@RequestMapping("/v1/admin/ops-pages")
public class OpsPageController {

	private final OpsPageApi pages;
	private final org.springframework.beans.factory.ObjectProvider<com.lrj.commerce.app.iam.CentralEmployeeService> central;

	public OpsPageController(OpsPageApi pages,
			org.springframework.beans.factory.ObjectProvider<com.lrj.commerce.app.iam.CentralEmployeeService> central) {
		this.pages = pages;
		this.central = central;
	}

	record Revision(long expectedVersion) {
	}

	/** 创建不可变页面版本。 */
	@PostMapping
	public Object create(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@RequestBody OpsPageApi.Definition input) {
		return pages.create(actor, key, input);
	}

	/** 最新版本列表。 */
	@GetMapping
	public Object list(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "") String after,
			@RequestParam(defaultValue = "50") int limit) {
		return pages.list(actor, after, limit);
	}

	/** 真实数据只读预览，不持久化页面。 */
	@PostMapping("/preview")
	public Object preview(@AuthenticationPrincipal Actor actor, @RequestBody OpsPageApi.Definition input,
			@RequestHeader(value="Authorization", required=false) String authorization,
			@RequestHeader(value="X-Tenant-Id", required=false) String tenant) {
		var prepared = pages.preparePreview(actor, input);
		return prepared.render(children(actor, prepared.capabilities(), authorization, tenant));
	}

	/** 发布版本渲染。 */
	@GetMapping("/{id}/render")
	public Object render(@AuthenticationPrincipal Actor actor, @PathVariable String id,
			@RequestHeader(value="Authorization", required=false) String authorization,
			@RequestHeader(value="X-Tenant-Id", required=false) String tenant) {
		var prepared = pages.prepareRender(actor, id);
		return prepared.render(children(actor, prepared.capabilities(), authorization, tenant));
	}

	/** 用于对比和回退的有界版本列表。 */
	@GetMapping("/{id}/versions")
	public Object versions(@AuthenticationPrincipal Actor actor, @PathVariable String id) {
		return pages.versions(actor, id);
	}

	/** 审批、发布或回退只能按合法迁移执行。 */
	@PostMapping("/{id}/{version}/{action}")
	public Object change(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@PathVariable String id, @PathVariable long version, @PathVariable String action,
			@RequestBody Revision input) {
		return pages.change(actor, key, id, version, input.expectedVersion(), action);
	}

	/** 执行固定白名单中的已声明业务动作。 */
	@PostMapping("/{id}/{version}/actions/{action}")
	public Object execute(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@PathVariable String id, @PathVariable long version, @PathVariable String action,
			@RequestBody OpsPageApi.ActionInput input,
			@RequestHeader(value="Authorization", required=false) String authorization,
			@RequestHeader(value="X-Tenant-Id", required=false) String tenant) {
		var prepared = pages.prepareExecution(actor, key, id, version, action, input);
		return prepared.execute(child(actor, prepared.capability(), authorization, tenant));
	}

	/** 每一段/动作重新签发自己的闭集引用；不把 page 引用扩大为任意子业务能力。 */
	private java.util.Map<com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability, Actor> children(Actor actor,
			java.util.Set<com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability> capabilities, String authorization, String tenant) {
		var result = new java.util.EnumMap<com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability, Actor>(com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.class);
		for (var capability : capabilities) result.put(capability, child(actor, capability, authorization, tenant));
		return result;
	}

	private Actor child(Actor actor, com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability capability, String authorization, String tenant) {
		if (actor.role() != Actor.Role.OPERATOR) return actor;
		if (tenant == null || authorization == null || !authorization.startsWith("Bearer "))
			throw new com.lrj.commerce.kernel.DomainException(com.lrj.commerce.kernel.DomainException.Code.FORBIDDEN, "缺少页面子能力身份");
		var service = central.getIfAvailable();
		if (service == null) throw new com.lrj.commerce.kernel.DomainException(com.lrj.commerce.kernel.DomainException.Code.UNAVAILABLE, "中央页面授权不可用");
		return service.authenticate(authorization.substring(7), tenant, capability, capability == com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.JOURNEY_INSTANCE_CREATE);
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
