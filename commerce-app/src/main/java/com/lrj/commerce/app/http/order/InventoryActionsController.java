package com.lrj.commerce.app.http.order;

import com.lrj.commerce.app.iam.CentralEmployeeService;
import com.lrj.commerce.runtime.api.identity.Actor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 库存员工页动作提示；过滤链已验证单Bearer及组织，不保存请求凭据。 */
@RestController
@ConditionalOnProperty(name = {"commerce.iam.store-read.enabled", "commerce.iam.employee.enabled"}, havingValue = "true")
@RequestMapping("/v1/operations/inventory")
public class InventoryActionsController {
    private final CentralEmployeeService service;
    public InventoryActionsController(CentralEmployeeService service) { this.service = service; }
    /** read确保可以查看该门店；receive作为独立提示，不授予提交资格。 */
    @GetMapping("/actions")
    public CentralEmployeeService.InventoryActions actions(@AuthenticationPrincipal Actor actor,
            @RequestHeader("Authorization") String authorization, @RequestHeader("X-Tenant-Id") String tenant,
            @RequestParam String storeId) {
        return service.actions(actor, authorization.substring("Bearer ".length()), tenant, storeId);
    }
}
