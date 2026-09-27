package com.lrj.commerce.member.application;

import com.lrj.commerce.runtime.api.RecoverableWork;
import org.springframework.context.annotation.*;

/** 会员域两个逐项重试车道登记为可恢复工作，由平台恢复服务统一授权、限定范围与审计。 */
@Configuration
class MemberRecoveryConfiguration {

	@Bean
	RecoverableWork pointsExpiryRecovery(MemberPointsService points) {
		return points.recoverable();
	}

	@Bean
	RecoverableWork cycleAssessmentRecovery(MemberCycleService cycles) {
		return cycles.recoverable();
	}

}
