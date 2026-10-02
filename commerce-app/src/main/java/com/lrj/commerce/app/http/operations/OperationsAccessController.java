package com.lrj.commerce.app.http.operations;

import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.kernel.DomainException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 独立短资格提示没有任务来源；集合资格不承诺具体门店或页面可写，提交仍核对 Owner 实际事实。 */
@RestController
@RequestMapping("/v1/operations")
public class OperationsAccessController {
    private static final java.util.Map<String,EmployeeAccess.Capability> CAPABILITIES = java.util.Map.ofEntries(
            java.util.Map.entry("orders/read", EmployeeAccess.Capability.ORDER_READ),
            java.util.Map.entry("orders/expire", EmployeeAccess.Capability.ORDER_EXPIRE),
            java.util.Map.entry("orders/expiry-retry", EmployeeAccess.Capability.ORDER_EXPIRY_RETRY),
            java.util.Map.entry("payments/read", EmployeeAccess.Capability.PAYMENT_READ),
            java.util.Map.entry("payments/reconcile", EmployeeAccess.Capability.PAYMENT_RECONCILE),
            java.util.Map.entry("fulfillments/read", EmployeeAccess.Capability.FULFILLMENT_READ),
            java.util.Map.entry("fulfillments/ship", EmployeeAccess.Capability.FULFILLMENT_SHIP),
            java.util.Map.entry("fulfillments/deliver", EmployeeAccess.Capability.FULFILLMENT_DELIVER),
            java.util.Map.entry("aftersales/read", EmployeeAccess.Capability.AFTERSALE_READ),
            java.util.Map.entry("aftersales/approve", EmployeeAccess.Capability.AFTERSALE_APPROVE),
            java.util.Map.entry("aftersales/reject", EmployeeAccess.Capability.AFTERSALE_REJECT),
            java.util.Map.entry("aftersales/receive-return", EmployeeAccess.Capability.AFTERSALE_RECEIVE_RETURN),
            java.util.Map.entry("refunds/read", EmployeeAccess.Capability.REFUND_READ),
            java.util.Map.entry("refunds/reconcile", EmployeeAccess.Capability.REFUND_RECONCILE),
            java.util.Map.entry("ops-pages/read", EmployeeAccess.Capability.OPS_PAGE_READ),
            java.util.Map.entry("ops-pages/create", EmployeeAccess.Capability.OPS_PAGE_CREATE),
            java.util.Map.entry("ops-pages/preview", EmployeeAccess.Capability.OPS_PAGE_PREVIEW),
            java.util.Map.entry("ops-pages/submit", EmployeeAccess.Capability.OPS_PAGE_SUBMIT),
            java.util.Map.entry("ops-pages/approve", EmployeeAccess.Capability.OPS_PAGE_APPROVE),
            java.util.Map.entry("ops-pages/reject", EmployeeAccess.Capability.OPS_PAGE_REJECT),
            java.util.Map.entry("ops-pages/publish", EmployeeAccess.Capability.OPS_PAGE_PUBLISH),
            java.util.Map.entry("ops-pages/pause", EmployeeAccess.Capability.OPS_PAGE_PAUSE),
            java.util.Map.entry("ops-pages/rollback", EmployeeAccess.Capability.OPS_PAGE_ROLLBACK),
            java.util.Map.entry("ops-pages/execute", EmployeeAccess.Capability.OPS_PAGE_EXECUTE),
            java.util.Map.entry("events/read", EmployeeAccess.Capability.EVENT_READ),
            java.util.Map.entry("events/pump", EmployeeAccess.Capability.EVENT_PUMP),
            java.util.Map.entry("events/retry", EmployeeAccess.Capability.EVENT_RETRY),
            java.util.Map.entry("runtime/read", EmployeeAccess.Capability.RUNTIME_READ),
            java.util.Map.entry("runtime/recover", EmployeeAccess.Capability.RUNTIME_RECOVER),
            java.util.Map.entry("runtime/replay-preview", EmployeeAccess.Capability.RUNTIME_REPLAY_PREVIEW),
            java.util.Map.entry("runtime/replay-create", EmployeeAccess.Capability.RUNTIME_REPLAY_CREATE),
            java.util.Map.entry("runtime/replay-control", EmployeeAccess.Capability.RUNTIME_REPLAY_CONTROL),
            java.util.Map.entry("dashboard/read", EmployeeAccess.Capability.DASHBOARD_READ));
    private final EmployeeAccess access;
    public OperationsAccessController(EmployeeAccess access) { this.access=access; }
    public record Qualification(boolean allowed) {}
    /** 精确能力闭集，不按浏览器输入拼接 capability，也不把依赖故障转成 allowed=false。 */
    @GetMapping("/{family}/{action}-access")
    public Qualification qualification(@AuthenticationPrincipal Actor actor, @PathVariable String family, @PathVariable String action) {
        var capability=CAPABILITIES.get(family+"/"+action);
        if(capability==null)throw new DomainException(DomainException.Code.NOT_FOUND,"未登记此资格入口");
        access.scope(actor,capability);
        return new Qualification(true);
    }
}
