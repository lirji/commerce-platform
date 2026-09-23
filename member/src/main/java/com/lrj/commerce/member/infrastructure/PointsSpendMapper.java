package com.lrj.commerce.member.infrastructure;
import com.lrj.commerce.member.api.PointsSpendApi;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** 持有及分配属于积分权威，不依赖交易表外键或跨域写入。 */
@Mapper
public interface PointsSpendMapper {
    record Hold(String memberId,long policyVersion,long points,String discount,String status,long returnedPoints) { }
    record Allocation(String lotId,long points,long returnedPoints) { }
    record Returned(String orderId,long points) { }
    Hold hold(@Param("tenant") String tenant,@Param("order") String order);
    void insert(@Param("tenant") String tenant,@Param("order") String order,@Param("member") String member,@Param("application") PointsSpendApi.Application application);
    int transition(@Param("tenant") String tenant,@Param("order") String order,@Param("before") String before,@Param("after") String after);
    void allocate(@Param("tenant") String tenant,@Param("order") String order,@Param("lot") String lot,@Param("points") long points,@Param("sequence") int sequence);
    List<Allocation> allocations(@Param("tenant") String tenant,@Param("order") String order);
    int allocationReturn(@Param("tenant") String tenant,@Param("order") String order,@Param("lot") String lot,@Param("points") long points);
    int returned(@Param("tenant") String tenant,@Param("order") String order,@Param("points") long points);
    Returned refund(@Param("tenant") String tenant,@Param("id") String id);
    void refundInsert(@Param("tenant") String tenant,@Param("order") String order,@Param("id") String id,@Param("points") long points);
}
