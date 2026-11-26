package com.lrj.commerce.store.management.infrastructure.persistence;

import com.lrj.commerce.store.management.api.PlatformCategoryApi;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 平台字典与专岗权限均为全局主数据，写入历史使用真实命令身份。 */
@Mapper
public interface PlatformCategoryMapper {

	List<PlatformCategoryApi.Node> list();

	PlatformCategoryApi.Node find(@Param("code") String code);

	PlatformCategoryApi.Node parentForCreate(@Param("code") String code);

	void insert(@Param("input") PlatformCategoryApi.Create input, @Param("depth") int depth);

	int change(@Param("code") String code, @Param("input") PlatformCategoryApi.Change input);

	int audit(@Param("code") String code, @Param("tenant") String tenant, @Param("actor") String actor,
			@Param("reason") String reason);

	List<PlatformCategoryApi.History> history(@Param("code") String code);

	boolean curator(@Param("tenant") String tenant, @Param("actor") String actor);

	Boolean lockCurator(@Param("tenant") String tenant, @Param("actor") String actor);
}
