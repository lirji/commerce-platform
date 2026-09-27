package com.lrj.commerce.app.configuration.alert;

import com.lrj.commerce.app.runtime.monitoring.BackgroundRuntime;
import com.lrj.commerce.app.runtime.monitoring.OperationalAlertPublisher;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

/** 未配置外部告警供应商时的默认出口：与第三阶段相同的日志行，按固定代码检索。 */
@Configuration
class AlertConfiguration {

	@Bean
	@ConditionalOnMissingBean
	OperationalAlertPublisher loggingAlertPublisher() {
		var log = LoggerFactory.getLogger(BackgroundRuntime.class);
		return (observedAt, codes) -> log.warn("background runtime alert codes={}", codes);
	}

}
