package com.lrj.commerce.app.http.commerce;

import com.lrj.commerce.runtime.api.validation.ListFilter;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.member.profile.api.MemberApi;
import com.lrj.commerce.merchant.api.MerchantApi;
import com.lrj.commerce.store.management.api.StoreApi;
import com.lrj.commerce.catalog.assortment.api.CatalogApi;
import com.lrj.commerce.campaign.management.api.CampaignApi;
import com.lrj.commerce.trade.api.QuoteApi;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 协议适配仅传递可信身份及DTO，业务规则与SQL分别留在用例和各域Mapper。 */
@RestController
@RequestMapping("/v1")
public class CommerceController {

	private final MemberApi members;

	private final MerchantApi merchants;

	private final StoreApi stores;

	private final CatalogApi catalog;

	private final CampaignApi campaigns;

	private final QuoteApi quotes;

	public CommerceController(MemberApi members, MerchantApi merchants, StoreApi stores, CatalogApi catalog,
			CampaignApi campaigns, QuoteApi quotes) {
		this.members = members;
		this.merchants = merchants;
		this.stores = stores;
		this.catalog = catalog;
		this.campaigns = campaigns;
		this.quotes = quotes;
	}

	@GetMapping("/me")
	public Actor me(@AuthenticationPrincipal Actor actor) {
		return actor;
	}

	@PostMapping("/admin/members")
	public Object member(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@RequestBody MemberApi.Create input) {
		return members.create(actor, key, input);
	}

	@GetMapping("/admin/members")
	public Object members(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "") String after,
			@RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) String q, @RequestParam(required = false) String status,
			@RequestParam(required = false) java.time.Instant from, @RequestParam(required = false) java.time.Instant to) {
		return members.list(actor, after, limit, new ListFilter(q, status, from, to));
	}

	@PostMapping("/admin/merchants")
	public Object merchant(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@RequestBody MerchantApi.Create input) {
		return merchants.create(actor, key, input);
	}

	@GetMapping("/admin/merchants")
	public Object merchants(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "") String after,
			@RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) String q, @RequestParam(required = false) String status,
			@RequestParam(required = false) java.time.Instant from, @RequestParam(required = false) java.time.Instant to) {
		return merchants.list(actor, after, limit, new ListFilter(q, status, from, to));
	}

	@PostMapping("/admin/stores")
	public Object store(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@RequestBody StoreApi.Create input) {
		return stores.create(actor, key, input);
	}

	@GetMapping("/admin/stores")
	public Object stores(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "") String after,
			@RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) String q, @RequestParam(required = false) String status,
			@RequestParam(required = false) java.time.Instant from, @RequestParam(required = false) java.time.Instant to) {
		return stores.list(actor, after, limit, new ListFilter(q, status, from, to));
	}

	@PostMapping("/admin/skus")
	public Object sku(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@RequestBody CatalogApi.Create input) {
		return catalog.create(actor, key, input);
	}

	@GetMapping({ "/catalog", "/admin/skus" })
	public Object catalog(@AuthenticationPrincipal Actor actor, @RequestParam String storeId,
			@RequestParam(defaultValue = "") String after, @RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) String q, @RequestParam(required = false) String status,
			@RequestParam(required = false) java.time.Instant from, @RequestParam(required = false) java.time.Instant to) {
		return catalog.list(actor, storeId, after, limit, new ListFilter(q, status, from, to));
	}

	@PostMapping("/admin/campaigns")
	public Object campaign(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@RequestBody CampaignApi.Draft input) {
		return campaigns.create(actor, key, input);
	}

	@GetMapping("/admin/campaigns")
	public Object campaigns(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "") String after,
			@RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) String q, @RequestParam(required = false) String status,
			@RequestParam(required = false) java.time.Instant from, @RequestParam(required = false) java.time.Instant to) {
		return campaigns.list(actor, after, limit, new ListFilter(q, status, from, to));
	}

	public record Version(long expectedVersion) {
	}

	@PostMapping("/admin/campaigns/{id}/{version}/publish")
	public Object publish(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@PathVariable String id, @PathVariable long version, @RequestBody Version input) {
		return campaigns.publish(actor, key, id, version, input.expectedVersion());
	}

	@PostMapping("/admin/campaigns/{id}/{version}/pause")
	public Object pause(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@PathVariable String id, @PathVariable long version, @RequestBody Version input) {
		return campaigns.pause(actor, key, id, version, input.expectedVersion());
	}

	@PostMapping("/quotes")
	public Object quote(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@RequestBody QuoteApi.Request input) {
		return quotes.create(actor, key, input);
	}

	@GetMapping("/quotes/{id}")
	public Object quote(@AuthenticationPrincipal Actor actor, @PathVariable String id) {
		return quotes.read(actor, id);
	}

}
