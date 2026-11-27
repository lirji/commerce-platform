package com.lrj.commerce.app.http.commerce;

import com.lrj.commerce.app.application.audit.OrderOperationEvidence;
import com.lrj.commerce.app.application.audit.OrderOperationEvidence.Page;
import com.lrj.commerce.app.application.audit.OrderOperationEvidence.Source;
import com.lrj.commerce.app.configuration.security.SecurityConfiguration;
import com.lrj.commerce.runtime.api.identity.Actor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 试点后台只接受原生交易展示编号，租户及店铺从后台权限和持久关联确定。 */
@RestController
@ConditionalOnProperty(name = {"commerce.checkout-pilot.enabled", "commerce.order-audit.enabled"}, havingValue = "true")
public class OrderOperationEvidenceController {
    private final OrderOperationEvidence evidence;

    public OrderOperationEvidenceController(OrderOperationEvidence evidence) {
        this.evidence = evidence;
    }

    @GetMapping("/v1/admin/order-operation-evidence")
    public ResponseEntity<Page> read(@AuthenticationPrincipal Actor actor, HttpServletRequest request,
            @RequestParam String orderNo, @RequestParam(defaultValue = "ALL") Source source,
            @RequestParam(required = false) String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                evidence.read(actor, orderNo, source, cursor, SecurityConfiguration.trace(request)));
    }
}
