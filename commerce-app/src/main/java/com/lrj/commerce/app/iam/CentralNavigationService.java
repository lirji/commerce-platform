package com.lrj.commerce.app.iam;

import com.lrj.authz.protocol.NavigationDtos.*;
import com.lrj.authz.sdk.*;
import com.lrj.commerce.runtime.api.identity.Actor;
import java.util.UUID;

/** 本人菜单提示复用显式本地身份桥，不通过单能力授权引用推导其他入口。 */
public final class CentralNavigationService {
    private final CentralAccessClient client;
    private final CentralStoreBindingMapper bindings;
    /** 服务凭据仅由既有私密中央配置装配，浏览器不能提供或覆盖。 */
    public CentralNavigationService(CentralAccessClient client,CentralStoreBindingMapper bindings) {
        this.client=client;this.bindings=bindings;
    }
    /** 每次中央新查询及本地映射都成功才返回，无访问能力仍可明确展示空导航。 */
    public View current(String token,String tenant,Long generation) {
        var view=client.navigation(token,new Request(tenant,generation,UUID.randomUUID().toString()));
        var context=view.context();
        var actor=bindings.find(context.tenantId(),context.principalId(),context.membershipId(),context.membershipGeneration());
        if(actor==null||actor.role()!=Actor.Role.OPERATOR)throw new AccessDeniedException("CENTRAL_BINDING_REQUIRED");
        return view;
    }
}
