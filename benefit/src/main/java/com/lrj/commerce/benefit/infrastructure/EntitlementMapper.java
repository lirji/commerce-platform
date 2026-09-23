package com.lrj.commerce.benefit.infrastructure;
import com.lrj.commerce.benefit.api.EntitlementApi.*;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;
/** 权益授予和定义额度均由本域独占持久化。 */
@Mapper
public interface EntitlementMapper {
    record DefinitionRow(String benefitId,long version,String storeId,String name,int units,int quota,Instant validFrom,Instant validTo,int validityDays,int reserved,int issued) { }
    void definition(@Param("tenant") String tenant,@Param("input") Definition input);
    DefinitionRow definitionFind(@Param("tenant") String tenant,@Param("id") String id,@Param("version") long version);
    List<DefinitionRow> definitions(@Param("tenant") String tenant,@Param("store") String store,@Param("after") String after,@Param("limit") int limit);
    int reserveQuota(@Param("tenant") String tenant,@Param("id") String id,@Param("version") long version);
    int finishQuota(@Param("tenant") String tenant,@Param("grant") View grant,@Param("issued") boolean issued);
    void grant(@Param("tenant") String tenant,@Param("id") String id,@Param("order") String order,@Param("member") String member,@Param("definition") DefinitionRow definition);
    View byOrder(@Param("tenant") String tenant,@Param("order") String order);
    View find(@Param("tenant") String tenant,@Param("id") String id);
    View lock(@Param("tenant") String tenant,@Param("id") String id);
    View lockOwned(@Param("tenant") String tenant,@Param("member") String member,@Param("id") String id);
    int change(@Param("tenant") String tenant,@Param("grant") View grant,@Param("status") State status,@Param("remaining") int remaining,@Param("debt") int debt,@Param("expires") Instant expires);
    int consume(@Param("tenant") String tenant,@Param("grant") View grant,@Param("units") int units);
    List<View> list(@Param("tenant") String tenant,@Param("member") String member,@Param("after") String after,@Param("limit") int limit);
    void entry(@Param("tenant") String tenant,@Param("id") String id,@Param("grant") String grant,@Param("action") String action,@Param("units") int units,@Param("balance") int balance,@Param("reference") String reference);
    List<Ledger> ledger(@Param("tenant") String tenant,@Param("grant") String grant,@Param("after") String after,@Param("limit") int limit);
}
