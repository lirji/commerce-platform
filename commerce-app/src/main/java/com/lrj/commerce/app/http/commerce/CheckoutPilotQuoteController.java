package com.lrj.commerce.app.http.commerce;

import com.lrj.commerce.app.application.checkout.CheckoutPilotQuoteService;
import com.lrj.commerce.app.configuration.security.CheckoutPilotSecurity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 交易仅可读取现有报价关联的门店证据，取得证据前必须通过独立机器授权。 */
@RestController
@ConditionalOnProperty(name = "commerce.checkout-pilot.enabled", havingValue = "true")
public class CheckoutPilotQuoteController {
	private final CheckoutPilotQuoteService service;

	public CheckoutPilotQuoteController(CheckoutPilotQuoteService service) {
		this.service = service;
	}

	/** 门店、商品与授权可变化，响应保持不可缓存。 */
	@GetMapping(CheckoutPilotSecurity.PATH)
	public ResponseEntity<CheckoutPilotQuoteService.Evidence> evidence(@PathVariable String quoteId) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(quoteId));
	}
}
