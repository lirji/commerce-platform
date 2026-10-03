package com.lrj.commerce.app.http.marketing.asset;

import com.lrj.commerce.runtime.api.validation.ListFilter;
import com.lrj.commerce.app.http.commerce.CommerceController;
import com.lrj.commerce.runtime.api.identity.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.lrj.commerce.campaign.asset.api.MarketingAssets;
import com.lrj.commerce.campaign.funding.api.CampaignFundingApi;
import com.lrj.commerce.campaign.management.api.CampaignApi;

/** 营销资产与审批协议，客户端不提交用于成交的事实值。 */
@RestController
@RequestMapping("/v1/admin")
public class MarketingAssetController {

	private final CampaignFundingApi funding;

	private final MarketingAssets assets;

	private final CampaignApi campaigns;

	public MarketingAssetController(MarketingAssets assets, CampaignApi campaigns, CampaignFundingApi funding) {
		this.funding = funding;
		this.assets = assets;
		this.campaigns = campaigns;
	}

	/** 只读模拟不走命令账本，没有额度或交易副作用。 */
	@PostMapping("/campaigns/{id}/{version}/preview")
	public Object preview(@AuthenticationPrincipal Actor actor, @PathVariable String id, @PathVariable long version,
			@RequestBody CampaignApi.Preview input) {
		return campaigns.preview(actor, id, version, input);
	}

	/** 明确来源水位与新鲜度的不可变人群导入。 */
	@PostMapping("/audiences")
	public Object audience(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@RequestBody MarketingAssets.Audience input) {
		return assets.createAudience(actor, key, input);
	}

	/** 只返回人群摘要，不泄漏全量会员清单。 */
	@GetMapping("/audiences")
	public Object audiences(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "") String after,
			@RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) String q, @RequestParam(required = false) String status,
			@RequestParam(required = false) java.time.Instant from, @RequestParam(required = false) java.time.Instant to) {
		return assets.audiences(actor, after, limit, new ListFilter(q, status, from, to));
	}

	/** 规则字段按服务器目录校验。 */
	@PostMapping("/rules")
	public Object rule(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@RequestBody MarketingAssets.Rule input) {
		return assets.createRule(actor, key, input);
	}

	/** 规则版本只发布一次，内容不原位改写。 */
	@PostMapping("/rules/{id}/{version}/publish")
	public Object publish(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@PathVariable String id, @PathVariable long version) {
		return assets.publishRule(actor, key, id, version);
	}

	/** 列最新版本规则资产。 */
	@GetMapping("/rules")
	public Object rules(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "") String after,
			@RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) String q, @RequestParam(required = false) String status,
			@RequestParam(required = false) java.time.Instant from, @RequestParam(required = false) java.time.Instant to) {
		return assets.rules(actor, after, limit, new ListFilter(q, status, from, to));
	}

	/** 字段类型来自固定可信数据提供方。 */
	@GetMapping("/rule-fields")
	public Object fields(@AuthenticationPrincipal Actor actor) {
		return assets.ruleFields(actor);
	}

	/** 路径只允许三个审批动作，发布继续走已冻结接口。 */
	@PostMapping("/campaigns/{id}/{version}/{action:submit|approve|reject}")
	public Object review(@AuthenticationPrincipal Actor actor, @RequestHeader("Idempotency-Key") String key,
			@PathVariable String id, @PathVariable long version, @PathVariable String action,
			@RequestBody CommerceController.Version input) {
		return campaigns.review(actor, key, id, version, input.expectedVersion(), action);
	}

	/** 预算余额来自权威数据库，旧版本仍可审计。 */
	@GetMapping("/campaign-budgets")
	public Object budgets(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "") String after,
			@RequestParam(defaultValue = "50") int limit,
			@RequestParam(required = false) String q, @RequestParam(required = false) String status,
			@RequestParam(required = false) java.time.Instant from, @RequestParam(required = false) java.time.Instant to) {
		return funding.budgets(actor, after, limit, new ListFilter(q, status, from, to));
	}

}
