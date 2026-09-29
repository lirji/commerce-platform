package com.lrj.commerce.catalog.product.api;

import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.scope.ScopeQuery;
import java.time.Instant;

/** 中央用例专用Owner端口；已验证的完整范围必须再次进入条件更新，不使用旧门店Grant回退。 */
public interface ScopedProductOperations {
    /** 可修改元资料，不接受归属门店、范围或身份字段。 */
    record MetadataChange(long expectedVersion,String title,String category,String brand) {}
    /** 受限决策与真实资源版本共同限制本地提交，幂等命令和审计同事务。 */
    ProductOperationsApi.Product changeScoped(Actor actor,ScopeQuery.Filter scope,Instant deadline,String requester,String key,String id,String store,MetadataChange input);
}
