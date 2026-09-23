package com.lrj.commerce.benefit.infrastructure;
import com.lrj.commerce.benefit.api.MemberBenefitApi;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 礼包不可变且策略/等级唯一，不跨域直接查询会员表。 */
@Mapper
public interface MemberBenefitMapper {
    void insert(@Param("tenant") String tenant,@Param("b") MemberBenefitApi.Bundle bundle,@Param("json") String json);
    String find(@Param("tenant") String tenant,@Param("policy") long policy,@Param("level") String level);
    List<String> list(@Param("tenant") String tenant,@Param("policy") long policy);
}
