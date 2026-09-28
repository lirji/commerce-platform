package com.lrj.commerce.app.iam;

import com.lrj.commerce.runtime.api.identity.Actor;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 身份桥只读自身商城表，不直连auth治理库。 */
@Mapper
public interface CentralStoreBindingMapper {
    /** 当前中央身份与显式本地运营映射必须同时匹配，不自动开户或提升ADMIN。 */
    Actor find(@Param("tenant") String tenant, @Param("principal") String principal,
               @Param("member") String member, @Param("generation") long generation);
}
