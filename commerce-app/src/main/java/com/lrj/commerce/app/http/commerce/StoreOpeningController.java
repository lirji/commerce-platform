package com.lrj.commerce.app.http.commerce;

import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.store.management.api.StoreOpeningApi;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 开店草稿与确认入口和旧目录命令并存，沿用权威运营身份。 */
@RestController
@RequestMapping("/v1/admin/store-openings")
public class StoreOpeningController {
	private final StoreOpeningApi openings;

	public StoreOpeningController(StoreOpeningApi openings) {
		this.openings = openings;
	}

	/** 保存待开店资料。 */
	@PostMapping
	public StoreOpeningApi.Opening draft(@AuthenticationPrincipal Actor actor,
			@RequestHeader("Idempotency-Key") String key, @RequestBody StoreOpeningApi.Draft input) {
		return openings.draft(actor, key, input);
	}

	/** 读取可继续办理的店铺资料。 */
	@GetMapping("/{storeId}")
	public StoreOpeningApi.Opening detail(@AuthenticationPrincipal Actor actor, @PathVariable String storeId) {
		return openings.detail(actor, storeId);
	}

	/** 按资料版本补正草稿。 */
	@PostMapping("/{storeId}/revise")
	public StoreOpeningApi.Opening revise(@AuthenticationPrincipal Actor actor,
			@RequestHeader("Idempotency-Key") String key, @PathVariable String storeId,
			@RequestBody StoreOpeningApi.Revision input) {
		return openings.revise(actor, key, storeId, input);
	}

	/** 记录审核依据并显式启用门店。 */
	@PostMapping("/{storeId}/open")
	public StoreOpeningApi.Opening open(@AuthenticationPrincipal Actor actor,
			@RequestHeader("Idempotency-Key") String key, @PathVariable String storeId,
			@RequestBody StoreOpeningApi.Activation input) {
		return openings.open(actor, key, storeId, input);
	}
}
