package com.lrj.commerce.marketing.domain;

import com.lrj.commerce.kernel.Money;
import com.lrj.commerce.marketing.api.DecisionModels.Line;
import com.lrj.commerce.marketing.api.DecisionModels.Selection;
import java.util.Comparator;
import java.util.List;

/** 当前产品只允许一个活动生效；显式固定 BEST_OF 规则以防候选读取顺序改变结果。 */
public final class CampaignConflictResolver {

	public record Eligible(Selection campaign, Money discount, List<Line> lines) {
	}

	/** 实际优惠最大者优先，同额以活动 ID 升序破平局，不引入未定义的叠加计算。 */
	public Eligible bestOf(List<Eligible> eligible) {
		return eligible.stream()
			.filter(item -> item.discount().compareTo(Money.ZERO) > 0)
			.min(Comparator.<Eligible, Money>comparing(Eligible::discount).reversed()
				.thenComparing(item -> item.campaign().campaignId()))
			.orElse(null);
	}
}
