package com.lrj.commerce.app.http.runtime.health;

import com.lrj.commerce.app.runtime.monitoring.BackgroundRuntime;
import com.lrj.commerce.runtime.api.identity.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 平台运维只读接口：跨租户聚合运行健康，路由层要求平台运维角色，用例层再校验专用能力。 */
@RestController
@RequestMapping("/v1/platform")
public class PlatformRuntimeController {

	private final BackgroundRuntime runtime;

	public PlatformRuntimeController(BackgroundRuntime runtime) {
		this.runtime = runtime;
	}

	/** 平台工作台只读取自身身份；不借租户/me入口附赠租户业务权限。 */
	@GetMapping("/me")
	public Actor me(@AuthenticationPrincipal Actor actor) {
		actor.require(Actor.Capability.EVENT_RUNTIME_METRICS_READ);
		return actor;
	}

	/** 事件与各后台车道的积压、轮转、调度延迟和告警代码，不含租户标识与载荷。 */
	@GetMapping("/runtime")
	public Object runtime(@AuthenticationPrincipal Actor actor) {
		return runtime.view(actor);
	}

}
