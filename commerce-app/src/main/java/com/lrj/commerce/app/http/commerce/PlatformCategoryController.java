package com.lrj.commerce.app.http.commerce;

import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.app.configuration.security.SecurityConfiguration;
import com.lrj.commerce.store.management.api.PlatformCategoryApi;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 平台类目维护使用既有平台安全边界，写权限由字典用例再次核验。 */
@RestController
@RequestMapping("/v1/platform/commerce-categories")
public class PlatformCategoryController {
	private final PlatformCategoryApi categories;

	public PlatformCategoryController(PlatformCategoryApi categories) {
		this.categories = categories;
	}

	/** 返回树所需节点，包括停用项。 */
	@GetMapping
	public PlatformCategoryApi.Directory directory(@AuthenticationPrincipal Actor actor) {
		return categories.directory(actor);
	}

	/** 创建分组或末级类目。 */
	@PostMapping
	public PlatformCategoryApi.Node create(@AuthenticationPrincipal Actor actor,
			@RequestHeader("Idempotency-Key") String key, @RequestBody PlatformCategoryApi.Create input,
			HttpServletRequest request) {
		return categories.create(actor, key, input, SecurityConfiguration.trace(request));
	}

	/** 改名、排序与启停使用一致的版本和幂等协议。 */
	@PostMapping("/{code}")
	public PlatformCategoryApi.Node change(@AuthenticationPrincipal Actor actor,
			@RequestHeader("Idempotency-Key") String key, @PathVariable String code,
			@RequestBody PlatformCategoryApi.Change input, HttpServletRequest request) {
		return categories.change(actor, key, code, input, SecurityConfiguration.trace(request));
	}

	/** 读取某个节点的最近变更。 */
	@GetMapping("/{code}/history")
	public List<PlatformCategoryApi.History> history(@AuthenticationPrincipal Actor actor, @PathVariable String code) {
		return categories.history(actor, code);
	}
}
