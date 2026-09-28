package com.lrj.commerce.catalog.product.application;
import com.lrj.commerce.catalog.product.infrastructure.persistence.CatalogScopeMapper;
import com.lrj.commerce.runtime.api.scope.ScopeQuery;
import com.lrj.commerce.runtime.api.identity.Actor;
import java.util.List;
import org.springframework.stereotype.Service;
/** 中央授权后唯一允许的字段绑定，仍由原资源模块读取自己的表。 */
@Service
public final class CatalogScopeQuery implements ScopeQuery {
    private final CatalogScopeMapper mapper;
    /** 依赖模块自己的持久化端口，不跨域读库。 */
    public CatalogScopeQuery(CatalogScopeMapper mapper){this.mapper=mapper;}
    /** 固定资源类型。 */
    public String resourceType(){return "product";}
    /** 已验证范围进入参数化分页。 */
    public List<Row> page(Actor actor,Filter scope,String search,String after,int limit){ScopeQuery.validate(actor,scope,search,after,limit);return mapper.page(actor.tenantId(),scope,search,after,limit);}
    /** 统计不使用另外一套授权条件。 */
    public Stats stats(Actor actor,Filter scope,String search){ScopeQuery.validate(actor,scope,search,"",1);return mapper.stats(actor.tenantId(),scope,search);}
    /** 可信事实只能从当前本地租户取得。 */
    public Row fact(Actor actor,String id){ScopeQuery.validate(actor,new Filter(List.of()),"",id,1);return mapper.fact(actor.tenantId(),id);}
    /** 下载验证每批最多50个ID，空批不产生无条件查询。 */
    public List<Row> current(Actor actor,Filter scope,List<String> ids){
        ScopeQuery.validate(actor,scope,"","",1);if(ids==null||ids.isEmpty()||ids.size()>50)throw new IllegalArgumentException("资源批次无效");
        return mapper.current(actor.tenantId(),scope,List.copyOf(ids));
    }
}
