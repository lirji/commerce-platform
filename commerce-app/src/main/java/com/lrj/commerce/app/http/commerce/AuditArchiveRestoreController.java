package com.lrj.commerce.app.http.commerce;

import com.lrj.commerce.app.configuration.security.SecurityConfiguration;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveVerification;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveVerification.RestorePage;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 恢复端点只使用已批准工单，身份、授权和证据批次都从服务端核实。 */
@RestController
@ConditionalOnProperty(name = "commerce.audit-archive.enabled", havingValue = "true")
@RequestMapping({"/v1/platform/audit-archive/restorations", "/v1/admin/audit-archive/restorations"})
public class AuditArchiveRestoreController {
    private final AuditArchiveVerification archive;

    public AuditArchiveRestoreController(AuditArchiveVerification archive) {
        this.archive = archive;
    }

    /** 同一工单重复执行仍只回填同一份独立恢复区。 */
    @PostMapping("/{ticket}/restore")
    public ResponseEntity<Map<String, Integer>> restore(@AuthenticationPrincipal Actor actor,
            @PathVariable String ticket, HttpServletRequest request) {
        int count = archive.restore(actor, ticket, SecurityConfiguration.trace(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("recordCount", count));
    }

    /** 只读受控查询区；已撤销或过期工单无法读取先前恢复副本。 */
    @GetMapping("/{ticket}")
    public ResponseEntity<RestorePage> read(@AuthenticationPrincipal Actor actor, @PathVariable String ticket,
            @RequestParam(defaultValue = "0") long after, HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                archive.restored(actor, ticket, after, SecurityConfiguration.trace(request)));
    }
}
