package com.lrj.commerce.app.iam;

import com.lrj.authz.protocol.NavigationDtos;
import com.lrj.authz.sdk.AccessDeniedException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 仅返回安全链本次核验的本人菜单提示，不提供角色／Grant管理明细。 */
@RestController
@ConditionalOnProperty(name={"commerce.iam.store-read.enabled","commerce.iam.navigation.enabled"},havingValue="true")
public class CentralNavigationController {
    /** 非本链身份没有响应，不把未知principal转换为默认管理员。 */
    @GetMapping("/v1/operations/navigation")
    public NavigationDtos.View current(@AuthenticationPrincipal NavigationDtos.View view) {
        if(view==null)throw new AccessDeniedException("CENTRAL_IDENTITY_REQUIRED");return view;
    }
}
