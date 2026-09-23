package com.lrj.commerce.runtime.persistence;
import org.apache.ibatis.annotations.*;

/** 可靠事件表由平台运行模块维护，业务效果必须与事件同事务。 */
@Mapper
public interface EventMapper {
    void insert(@Param("id") String id,@Param("tenant") String tenant,@Param("type") String type,@Param("aggregate") String aggregate,@Param("version") long version,@Param("json") String json);
}
