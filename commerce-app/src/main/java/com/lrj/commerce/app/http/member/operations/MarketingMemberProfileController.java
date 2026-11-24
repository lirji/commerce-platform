package com.lrj.commerce.app.http.member.operations;

import com.lrj.commerce.app.configuration.security.MarketingProfileSecurity;
import com.lrj.commerce.member.profile.api.MemberApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只向有固定租户授权的营销服务提供有界的画像读取。 */
@RestController
@ConditionalOnProperty(name = "commerce.marketing.member-profile.enabled", havingValue = "true")
public class MarketingMemberProfileController {
	private static final String LEVEL_SUMMARY_VIEW = "view=level-summary";

	private final MemberApi memberApi;
	private final MarketingProfileSecurity authorization;

	public MarketingMemberProfileController(MemberApi memberApi, MarketingProfileSecurity authorization) {
		this.memberApi = memberApi;
		this.authorization = authorization;
	}

	/** 快照不可由网关或客户端缓存，下一次读取应反映最新账户状态。 */
	@GetMapping(MarketingProfileSecurity.PATH)
	public ResponseEntity<MemberApi.MarketingSnapshot> snapshot() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
			.body(memberApi.marketingSnapshot(authorization.commerceTenant()));
	}

	/** 汇总使用同一机器授权和固定租户，响应不含会员标识。 */
	@GetMapping(value = MarketingProfileSecurity.PATH, params = LEVEL_SUMMARY_VIEW)
	public ResponseEntity<MemberApi.MarketingLevelSummary> levelSummary() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(memberApi.marketingLevelSummary(authorization.commerceTenant()));
	}

}
