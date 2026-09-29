package com.lrj.commerce.app.iam;

import com.lrj.authz.protocol.CentralAccessDtos.Check;
import com.lrj.authz.protocol.ScopeDtos.Facts;
import com.lrj.authz.sdk.CentralAccessClient;
import com.lrj.authz.sdk.AccessDeniedException;
import com.lrj.commerce.store.access.api.CentralCatalogCheck;
import com.lrj.commerce.store.access.api.CatalogAuthorityRoutes;
import com.lrj.commerce.store.management.api.StoreApi;
import com.lrj.commerce.runtime.api.identity.Actor;
import java.time.Instant;
import java.util.*;

/** 完整CATALOG桥只代理经过认证的运营身份，后台任务复用同一实时检查。 */
public final class CentralCatalogService implements CentralCatalogCheck {
    public static final String CAPABILITY="commerce.catalog.operate";
    private final CentralAccessClient client;
    private final CentralStoreBindingMapper bindings;
    private final CatalogAuthorityRoutes routes;
    public CentralCatalogService(CentralAccessClient client,CentralStoreBindingMapper bindings,CatalogAuthorityRoutes routes){this.client=client;this.bindings=bindings;this.routes=routes;}
    /** 使用中央Token签发不可扩权的执行引用，本地失效身份不能获得经营入口。 */
    public Actor authenticate(String token,String tenant,Instant until){
        var route=routes.central(tenant);if(route==null)throw denied();
        var ref=client.issueExecution(token,new Check(tenant,null,UUID.randomUUID().toString(),CAPABILITY,"store"),until.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        var c=ref.context();var actor=bindings.find(tenant,c.principalId(),c.membershipId(),c.membershipGeneration());
        if(actor==null||!actor.tenantId().equals(route.tenantId()))throw denied();
        return new Actor(actor.tenantId(),actor.actorId(),actor.role(),actor.channel(),ref.executionId());
    }
    /** 每次资源使用都从Owner事实构造请求；返回主体必须仍映射为原Actor。 */
    @Override public void require(Actor actor,StoreApi.View store,String authTenant){
        if(actor.role()!=Actor.Role.OPERATOR||actor.executionId()==null)throw denied();
        var request=new Check(authTenant,null,UUID.randomUUID().toString(),CAPABILITY,"store");
        var facts=new Facts(authTenant,"store",store.storeId(),store.version(),null,null,List.of(),store.storeId(),null);
        try {
            var result=client.checkExecution(actor.executionId(),request,facts);var c=result.context();
            if(!"ALLOW".equals(result.decision()))throw denied();
            var current=bindings.find(authTenant,c.principalId(),c.membershipId(),c.membershipGeneration());
            if(current==null||!actor.tenantId().equals(current.tenantId())||!actor.actorId().equals(current.actorId()))throw denied();
        } catch(com.lrj.authz.sdk.CentralAccessException failure) {
            throw new com.lrj.commerce.kernel.DomainException(com.lrj.commerce.kernel.DomainException.Code.UNAVAILABLE,"中央经营授权暂不可用");
        } catch(AccessDeniedException failure) {
            throw new com.lrj.commerce.kernel.DomainException(com.lrj.commerce.kernel.DomainException.Code.FORBIDDEN,"中央经营授权拒绝");
        }
    }
    private static AccessDeniedException denied(){return new AccessDeniedException("CENTRAL_CATALOG_DENIED");}
}
