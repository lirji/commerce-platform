package com.lrj.commerce.app.http.commerce;

import com.lrj.commerce.app.configuration.security.SecurityConfiguration;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.audit.OperationAudit;
import com.lrj.commerce.store.management.api.StoreOpeningApi;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 开店草稿与确认入口和旧目录命令并存，沿用权威运营身份。 */
@RestController
@RequestMapping("/v1/admin")
public class StoreOpeningController {
	private final StoreOpeningApi openings;

	public StoreOpeningController(StoreOpeningApi openings) {
		this.openings = openings;
	}

	/** 新店在拥有门店商品分类前即可获取平台经营类目。 */
	@GetMapping("/store-opening-categories")
	public List<StoreOpeningApi.Category> categories(@AuthenticationPrincipal Actor actor) {
		return openings.categories(actor);
	}

	/** 保存待开店资料。 */
	@PostMapping("/store-openings")
	public StoreOpeningApi.Opening draft(@AuthenticationPrincipal Actor actor,
			@RequestHeader("Idempotency-Key") String key, @RequestBody StoreOpeningApi.Draft input,
			HttpServletRequest request) {
		return openings.draft(actor, key, input, SecurityConfiguration.trace(request));
	}

	/** 读取可继续办理的店铺资料。 */
	@GetMapping("/store-openings/{storeId}")
	public StoreOpeningApi.Opening detail(@AuthenticationPrincipal Actor actor, @PathVariable String storeId) {
		return openings.detail(actor, storeId);
	}

	/** 查本店铺开店动作，租户与实际授权均由开店用例校验。 */
	@GetMapping("/store-openings/{storeId}/operation-audits")
	public OperationAudit.Page history(@AuthenticationPrincipal Actor actor, @PathVariable String storeId,
			@RequestParam(required = false) String cursor, HttpServletRequest request) {
		return openings.history(actor, storeId, SecurityConfiguration.trace(request), cursor);
	}

	/** 按资料版本补正草稿。 */
	@PostMapping("/store-openings/{storeId}/revise")
	public StoreOpeningApi.Opening revise(@AuthenticationPrincipal Actor actor,
			@RequestHeader("Idempotency-Key") String key, @PathVariable String storeId,
			@RequestBody StoreOpeningApi.Revision input, HttpServletRequest request) {
		return openings.revise(actor, key, storeId, input, SecurityConfiguration.trace(request));
	}

	/** 记录审核依据并显式启用门店。 */
	@PostMapping("/store-openings/{storeId}/open")
	public StoreOpeningApi.Opening open(@AuthenticationPrincipal Actor actor,
			@RequestHeader("Idempotency-Key") String key, @PathVariable String storeId,
			@RequestBody StoreOpeningApi.Activation input, HttpServletRequest request) {
		return openings.open(actor, key, storeId, input, SecurityConfiguration.trace(request));
	}
}
