package com.lrj.commerce.app.http.member.behavior;

import com.lrj.commerce.catalog.assortment.api.CatalogApi;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.app.application.member.MemberBehaviorRebuildService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import com.lrj.commerce.member.behavior.api.MemberBehaviorApi;
import com.lrj.commerce.member.profile.api.MemberApi;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.validation.Inputs;

/** app装配目录、订单与会员端口，领域不反向依赖交易。 */
@RestController
@RequestMapping("/v1")
public class MemberBehaviorController {

	private final MemberBehaviorApi behavior;

	private final MemberApi members;

	private final CatalogApi catalog;

	private final MemberBehaviorRebuildService rebuild;

	private final Commands commands;

	public MemberBehaviorController(MemberBehaviorApi behavior, MemberApi members, CatalogApi catalog, MemberBehaviorRebuildService rebuild,
			Commands commands) {
		this.behavior = behavior;
		this.members = members;
		this.catalog = catalog;
		this.rebuild = rebuild;
		this.commands = commands;
	}

	/** 员工会员详情由领域核验独立行为读取权限。 */
	@GetMapping("/admin/member-behavior/{id}")
	public Object detail(@AuthenticationPrincipal Actor actor, @PathVariable String id) {
		return behavior.detail(actor, id);
	}

	/** 管理更新偏好保留原因审计。 */
	@PostMapping("/admin/member-behavior/{id}/profile")
	public Object profile(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@PathVariable String id, @RequestBody MemberBehaviorApi.ProfileChange input) {
		return behavior.profile(actor, key, id, input);
	}

	/** 管理行为明细不允许跨租户。 */
	@GetMapping("/admin/member-behavior/{id}/events")
	public Object events(@AuthenticationPrincipal Actor actor, @PathVariable String id,
			@RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "50") int limit) {
		return behavior.events(actor, id, after, limit);
	}

	/** 本人资料和统计。 */
	@GetMapping("/members/me/behavior")
	public Object mine(@AuthenticationPrincipal Actor actor) {
		return behavior.detail(actor, members.current(actor).memberId());
	}

	/** 本人可关闭旅程触达。 */
	@PostMapping("/members/me/behavior/profile")
	public Object mineProfile(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@RequestBody MemberBehaviorApi.ProfileChange input) {
		return behavior.profile(actor, key, members.current(actor).memberId(), input);
	}

	/** 商品交互必须指向真实可售目录，不能写任意SKU统计。 */
	@PostMapping("/members/me/behavior/events")
	public Object record(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@RequestBody MemberBehaviorApi.Signal input) {
		Inputs.require(input != null, "行为内容缺失");
		com.lrj.commerce.kernel.Identifiers.require(input.skuId());
		com.lrj.commerce.kernel.Identifiers.require(input.storeId());
		if (actor.role() != Actor.Role.MEMBER)
			throw new com.lrj.commerce.kernel.DomainException(com.lrj.commerce.kernel.DomainException.Code.FORBIDDEN,
					"仅会员可记录交互");
		return commands.run(actor, "member.behavior.record", key, input, MemberBehaviorApi.Event.class, () -> {
			catalog.published(actor, input.storeId(), List.of(input.skuId()));
			return behavior.record(actor, input);
		});
	}

	/** 本人行为记录。 */
	@GetMapping("/members/me/behavior/events")
	public Object mineEvents(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "0") long after,
			@RequestParam(defaultValue = "50") int limit) {
		return behavior.events(actor, members.current(actor).memberId(), after, limit);
	}

	/** 重建独立授权，领域服务只消费真实订单来源，不复用管理员订单列表。 */
	@PostMapping("/admin/member-behavior/rebuild")
	public MemberBehaviorRebuildService.Progress rebuild(@AuthenticationPrincipal Actor actor,
			@RequestHeader("Idempotency-Key") String key, @RequestBody MemberBehaviorRebuildService.Rebuild input) {
		return rebuild.rebuild(actor, key, input);
	}

}
