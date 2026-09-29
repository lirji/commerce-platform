package com.lrj.commerce.store.access.application;

import com.lrj.commerce.store.access.api.CentralCatalogCheck;
import com.lrj.commerce.store.access.infrastructure.persistence.CatalogAuthorityMapper;
import com.lrj.commerce.store.management.api.StoreApi;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.kernel.DomainException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 单元权威由数据库唯一选择；中央失败不尝试旧管理员或本地Grant。 */
@Component
public class CatalogAuthority implements com.lrj.commerce.store.access.api.CatalogAuthorityRoutes {
    private final CatalogAuthorityMapper routes;
    private final ObjectProvider<CentralCatalogCheck> central;
    public CatalogAuthority(CatalogAuthorityMapper routes,ObjectProvider<CentralCatalogCheck> central){this.routes=routes;this.central=central;}
    /** 对认证层只暴露已接管的路由，写状态仍由迁移工具受控执行。 */
    @Override public Route central(String authTenant){return routes.central(authTenant);}
    /** true表示已经由中央完成校验，false才允许调用旧判权。 */
    public boolean require(Actor actor,StoreApi.View store){
        var route=routes.find(actor.tenantId());
        if(route==null||State.fromCode(route.state()).legacyAuthority()) {
            if(actor.executionId()!=null)throw denied();
            return false;
        }
        if(State.fromCode(route.state())!=State.CENTRAL||actor.executionId()==null)throw denied();
        var adapter=central.getIfAvailable();if(adapter==null)throw denied();
        adapter.require(actor,store,route.authTenantId());return true;
    }
    /** 迁移后旧门店列表不能绕过中央范围；P5范围接口负责中央列表。 */
    public void requireLegacyRead(Actor actor){var r=routes.find(actor.tenantId());if(r!=null&&!State.fromCode(r.state()).legacyAuthority())throw denied();}
    /** 提前给出业务错误，事务中的数据库触发器承担最终冻结约束。 */
    public void requireLegacyWrite(Actor actor){var r=routes.find(actor.tenantId());if(r!=null&&r.frozen())throw denied();}
    private static DomainException denied(){return new DomainException(DomainException.Code.FORBIDDEN,"经营授权权威不允许该入口");}
}
