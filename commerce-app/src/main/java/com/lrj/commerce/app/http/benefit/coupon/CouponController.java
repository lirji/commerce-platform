package com.lrj.commerce.app.http.benefit.coupon;

import com.lrj.commerce.runtime.api.validation.ListFilter;
import com.lrj.commerce.benefit.coupon.api.CouponApi;
import com.lrj.commerce.runtime.api.identity.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 券的资格和发行额度全部在服务端判断。 */
@RestController
@RequestMapping("/v1")
public class CouponController {

	private final CouponApi coupons;

	public CouponController(CouponApi coupons) {
		this.coupons = coupons;
	}

	/** 创建不可变券定义版本。 */
	@PostMapping("/admin/coupon-definitions")
	public Object create(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@RequestBody CouponApi.Definition input) {
		return coupons.create(actor, key, input);
	}

	/** 消费者和管理者读取相同权威定义。 */
	@GetMapping({ "/coupon-definitions", "/admin/coupon-definitions" })
	public Object definitions(@AuthenticationPrincipal Actor actor, @RequestParam String storeId,
			@RequestParam(defaultValue = "") String after, @RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) String q, @RequestParam(required = false) String status,
			@RequestParam(required = false) java.time.Instant from, @RequestParam(required = false) java.time.Instant to) {
		return coupons.definitions(actor, storeId, after, limit, new ListFilter(q, status, from, to));
	}

	/** 同会员同券版本最多领取一次。 */
	@PostMapping("/coupons/{id}/{version}/claim")
	public Object claim(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@PathVariable String id, @PathVariable long version) {
		return coupons.claim(actor, key, id, version);
	}

	/** 钱包只返回本人权益。 */
	@GetMapping("/coupons")
	public Object wallet(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "") String after,
			@RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) String q, @RequestParam(required = false) String status,
			@RequestParam(required = false) java.time.Instant from, @RequestParam(required = false) java.time.Instant to) {
		return coupons.wallet(actor, after, limit, new ListFilter(q, status, from, to));
	}

}
