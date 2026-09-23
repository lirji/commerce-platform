package com.lrj.commerce.aftersales.infrastructure;
import com.lrj.commerce.aftersales.api.AftersaleApi;
import org.apache.ibatis.annotations.*;
import java.util.List;
/** 活动申请唯一键和行退货累计由售后域拥有。 */
@Mapper
public interface AftersaleMapper {
    record Row(String caseId,String orderId,String memberId,String status,boolean returnRequired,String refundAmount,String refundId,long version,String itemsJson) { }
    record Returned(String skuId,int quantity) { }
    void insert(@Param("tenant") String tenant,@Param("view") AftersaleApi.View view,@Param("reason") String reason,@Param("items") String items);
    void line(@Param("tenant") String tenant,@Param("caseId") String caseId,@Param("line") AftersaleApi.Line line);
    Row find(@Param("tenant") String tenant,@Param("id") String id);
    Row lock(@Param("tenant") String tenant,@Param("id") String id);
    List<Row> list(@Param("tenant") String tenant,@Param("member") String member,@Param("after") String after,@Param("limit") int limit);
    List<Returned> returned(@Param("tenant") String tenant,@Param("order") String order);
    int change(@Param("tenant") String tenant,@Param("id") String id,@Param("version") long version,@Param("status") String status,@Param("refundId") String refundId);
}
