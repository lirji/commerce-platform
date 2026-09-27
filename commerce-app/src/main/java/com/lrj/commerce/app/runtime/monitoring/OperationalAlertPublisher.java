package com.lrj.commerce.app.runtime.monitoring;

import java.time.Instant;
import java.util.List;

/**
 * 运维告警出口：只传固定告警代码（CODE或CODE:车道），不含租户、载荷或异常文本。
 * 外部告警供应商尚未选定；选定后以另一个实现接入（例如按代码路由到值班渠道），默认实现只写一行结构化WARN日志。 实现抛出的异常只记录类型，不影响任何后台车道。
 */
public interface OperationalAlertPublisher {

	void publish(Instant observedAt, List<String> codes);

}
