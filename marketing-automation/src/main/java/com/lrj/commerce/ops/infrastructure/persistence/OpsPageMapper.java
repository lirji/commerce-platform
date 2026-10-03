package com.lrj.commerce.ops.infrastructure.persistence;

import com.lrj.commerce.ops.api.OpsPageApi.Definition;
import org.apache.ibatis.annotations.Mapper;
import java.util.List;

/** 页面版本与发布状态由运营模块独占，业务数据经API读取。 */
@Mapper
public interface OpsPageMapper {

	record Row(String pageId, long version, String definitionJson, String status, long lockVersion) {
	}

	void insert(String tenant, Definition input, String json);

	Row find(String tenant, String id, long version);

	Row published(String tenant, String id);

	List<Row> list(String tenant, String after, int limit, @org.apache.ibatis.annotations.Param("filter") com.lrj.commerce.runtime.api.validation.ListFilter filter);

	/** 兼容内部既有读取调用，缺省时不附加筛选条件。 */
	default List<Row> list(String tenant, String after, int limit) {
		return list(tenant, after, limit, com.lrj.commerce.runtime.api.validation.ListFilter.none());
	}

	List<Row> versions(String tenant, String id);

	List<Row> lockGroup(String tenant, String id);

	int pauseOthers(String tenant, String id);

	int change(String tenant, String id, long version, long expected, String status);

}
