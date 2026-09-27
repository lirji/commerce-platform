package com.lrj.commerce.app.http.runtime;

import com.lrj.commerce.app.runtime.BackgroundRuntime;
import com.lrj.commerce.runtime.api.Actor;
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

	/** 事件与各后台车道的积压、轮转、调度延迟和告警代码，不含租户标识与载荷。 */
	@GetMapping("/runtime")
	public Object runtime(@AuthenticationPrincipal Actor actor) {
		return runtime.view(actor);
	}

}
