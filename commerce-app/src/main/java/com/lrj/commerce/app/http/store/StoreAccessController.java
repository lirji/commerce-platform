package com.lrj.commerce.app.http.store;

import com.lrj.commerce.runtime.api.validation.ListFilter;
import com.lrj.commerce.store.access.api.StoreAccessApi;
import com.lrj.commerce.app.iam.CentralStoreReadService;
import com.lrj.commerce.app.iam.CentralStoreIdentity;
import java.util.Optional;
import com.lrj.commerce.runtime.api.identity.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 平台授权与运营资源查询分离，路由和用例均执行权限检查。 */
@RestController
@RequestMapping("/v1")
public class StoreAccessController {

	private final StoreAccessApi access;

	private final Optional<CentralStoreReadService> central;

	public StoreAccessController(StoreAccessApi access, Optional<CentralStoreReadService> central) {
		this.access = access;
		this.central = central;
	}

	/** 新建有原因的资源授权。 */
	@PostMapping("/admin/store-grants")
	public Object create(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@RequestBody StoreAccessApi.Create input) {
		return access.create(actor, key, input);
	}

	/** 版本化撤销或恢复。 */
	@PostMapping("/admin/store-grants/{id}/status")
	public Object change(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@PathVariable String id, @RequestBody StoreAccessApi.Change input) {
		return access.change(actor, key, id, input);
	}

	/** 授权审查列表。 */
	@GetMapping("/admin/store-grants")
	public Object list(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "") String after,
			@RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) String q, @RequestParam(required = false) String status,
			@RequestParam(required = false) java.time.Instant from, @RequestParam(required = false) java.time.Instant to, @RequestParam(required = false) Boolean enabled) {
		return access.list(actor, after, limit, new ListFilter(q, status, from, to, enabled));
	}

	/** 运营仅见被授权资源。 */
	@GetMapping("/operations/stores")
	public Object stores(@AuthenticationPrincipal Object principal, @RequestParam(defaultValue = "") String after,
			@RequestParam(defaultValue = "50") int limit) {
		if (central.isPresent()) {
			if (!(principal instanceof CentralStoreIdentity identity)) throw new com.lrj.authz.sdk.AccessDeniedException("CENTRAL_IDENTITY_REQUIRED");
			return central.get().read(identity, after, limit);
		}
		if (!(principal instanceof Actor actor)) throw new com.lrj.commerce.kernel.DomainException(com.lrj.commerce.kernel.DomainException.Code.FORBIDDEN, "需要运营身份");
		return access.stores(actor, after, limit);
	}

}
