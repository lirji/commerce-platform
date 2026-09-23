package com.lrj.commerce.trade.infrastructure;
import com.lrj.commerce.trade.api.QuoteApi.View;
import org.apache.ibatis.annotations.*;

/** 报价快照和消费权由交易模块独占。 */
@Mapper
public interface QuoteMapper {
    void insert(@Param("tenant") String tenant,@Param("view") View view,@Param("json") String json);
    String read(@Param("tenant") String tenant,@Param("member") String member,@Param("id") String id);
}
