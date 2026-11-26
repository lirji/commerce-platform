package com.lrj.commerce.trade.infrastructure.persistence;

import com.lrj.commerce.trade.api.QuoteApi.View;
import org.apache.ibatis.annotations.*;

/** 报价快照和消费权由交易模块独占。 */
@Mapper
public interface QuoteMapper {

	java.util.List<String> batch(@Param("tenant") String tenant, @Param("ids") java.util.List<String> ids);

	record Consumption(String snapshotJson, String consumedOrderId, java.time.Instant expiresAt) {
	}

	void insert(@Param("tenant") String tenant, @Param("view") View view, @Param("json") String json);

	/** 只在插入失败后确认报价展示号冲突。 */
	String lockBusinessNumber(@Param("number") String number);

	String read(@Param("tenant") String tenant, @Param("member") String member, @Param("id") String id);

	Consumption lock(@Param("tenant") String tenant, @Param("member") String member, @Param("id") String id);

	int consume(@Param("tenant") String tenant, @Param("id") String id, @Param("order") String order);

}
