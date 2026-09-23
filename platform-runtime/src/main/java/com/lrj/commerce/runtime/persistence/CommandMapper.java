package com.lrj.commerce.runtime.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 仅拥有平台命令和审计表；不接触领域表。 */
@Mapper
public interface CommandMapper {
    record Key(String tenantId, String actorId, String operation, String commandKey) { }
    record Row(String requestHash, String responseJson) { }
    void claim(@Param("key") Key key, @Param("hash") String hash);
    Row lock(Key key);
    int complete(@Param("key") Key key, @Param("response") String response);
    void audit(@Param("key") Key key);
}
