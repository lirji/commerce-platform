package com.lrj.commerce.app.iam;

import com.lrj.authz.protocol.CentralAccessDtos.Check;
import com.lrj.authz.sdk.CentralAccessClient;
import com.lrj.authz.sdk.AccessDeniedException;
import com.lrj.commerce.store.management.api.StoreApi;
import com.lrj.commerce.runtime.api.identity.Actor;
import java.util.List;
import java.util.UUID;

/** 首个只读业务接入，所有调用（包括非HTTP）都在用例入口重新判权。 */
public final class CentralStoreReadService {
    private final CentralAccessClient client;
    private final CentralStoreBindingMapper bindings;
    private final StoreApi stores;
    private final CentralScopeService scoped;
    /** 只依赖门店公开API，资源数据和SQL仍由商城拥有。 */
    public CentralStoreReadService(CentralAccessClient client, CentralStoreBindingMapper bindings, StoreApi stores) {
        this(client,bindings,stores,null);
    }
    /** P3启用时同一路由由范围服务接管，不降级回P2全范围。 */
    public CentralStoreReadService(CentralAccessClient client,CentralStoreBindingMapper bindings,StoreApi stores,CentralScopeService scoped){
        this.client=client;this.bindings=bindings;this.stores=stores;this.scoped=scoped;
    }
    /** 中央ALLOW之后必须匹配显式本地身份，不能将未知主体默认当成管理员。 */
    public CentralStoreIdentity authenticate(String token, String tenant) {
        if(scoped!=null)return scoped.authenticate(token,tenant,"store");
        var decision = client.requireAllowed(token, new Check(tenant, null, UUID.randomUUID().toString(), "commerce.store.read", "store"));
        var c = decision.context();
        Actor actor = bindings.find(c.tenantId(), c.principalId(), c.membershipId(), c.membershipGeneration());
        if (actor == null || actor.role() != Actor.Role.OPERATOR) throw new AccessDeniedException("CENTRAL_BINDING_REQUIRED");
        return new CentralStoreIdentity(token, tenant, c.membershipGeneration(), actor);
    }
    /** 当前TENANT_ALL经过中央确认；原StoreApi使用可信本地tenant在分页前过滤。 */
    public List<StoreApi.View> read(CentralStoreIdentity identity, String after, int limit) {
        if (identity == null) throw new AccessDeniedException("CENTRAL_IDENTITY_REQUIRED");
        if(scoped!=null)return scoped.page(identity,"store",after,limit,"").items().stream()
            .map(row->new StoreApi.View(row.resourceId(),row.merchantId(),row.title(),row.status(),row.resourceVersion())).toList();
        var current = authenticate(identity.userToken(), identity.authTenant());
        if (current.generation() != identity.generation() || !current.actor().equals(identity.actor())) throw new AccessDeniedException("CENTRAL_CONTEXT_CHANGED");
        return stores.browse(current.actor(), after, limit);
    }
}
