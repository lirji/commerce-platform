package com.lrj.commerce.merchant.infrastructure;
import com.lrj.commerce.merchant.api.MerchantApi;
import org.apache.ibatis.annotations.*;
import java.util.List;
/** 仅访问merchant权威表，所有谓词绑定可信tenant。 */
@Mapper
public interface MerchantMapper {
 void insert(@Param("tenant") String tenant,@Param("input") MerchantApi.Create input);
 MerchantApi.View find(@Param("tenant") String tenant,@Param("id") String id);
 List<MerchantApi.View> list(@Param("tenant") String tenant,@Param("after") String after,@Param("limit") int limit);
 
}
