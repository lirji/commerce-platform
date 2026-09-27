package com.lrj.commerce.app.http.marketing.execution;

import com.lrj.commerce.campaign.execution.api.CampaignExecutionApi;
import com.lrj.commerce.runtime.api.identity.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 本租户营销参与检查入口；失败事件只返回有限分类和现有恢复引用。 */
@RestController
@RequestMapping("/v1/admin/marketing-executions")
public class CampaignExecutionController {

	private final CampaignExecutionApi executions;

	public CampaignExecutionController(CampaignExecutionApi executions) {
		this.executions = executions;
	}

	@GetMapping
	public Object list(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "") String after,
			@RequestParam(defaultValue = "50") int limit) {
		return executions.list(actor, after, limit);
	}

	@GetMapping("/{orderId}/{campaignId}")
	public Object find(@AuthenticationPrincipal Actor actor, @PathVariable String orderId,
			@PathVariable String campaignId) {
		return executions.find(actor, orderId, campaignId);
	}

}
