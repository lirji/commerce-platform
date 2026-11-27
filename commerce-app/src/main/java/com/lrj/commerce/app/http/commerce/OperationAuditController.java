package com.lrj.commerce.app.http.commerce;

import com.lrj.commerce.app.configuration.security.SecurityConfiguration;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.audit.OperationAudit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 平台审计查询入口，业务层再次核对权限和查询范围。 */
@RestController
@RequestMapping("/v1/platform/operation-audits")
public class OperationAuditController {

	private final OperationAudit audit;

	public OperationAuditController(OperationAudit audit) {
		this.audit = audit;
	}

	/** 通过时间和流水游标有界读取，并记录本次查询。 */
	@GetMapping
	public OperationAudit.Page list(@AuthenticationPrincipal Actor actor, HttpServletRequest request,
			@RequestParam(required = false) String from, @RequestParam(required = false) String to,
			@RequestParam(required = false) String actorId, @RequestParam(required = false) String resourceId,
			@RequestParam(required = false) String action, @RequestParam(required = false) String result,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
		return audit.list(actor, SecurityConfiguration.trace(request), from, to, actorId, resourceId, action, result,
				cursor, limit);
	}

	/** 读取指定尝试时保留相应证据访问记录。 */
	@GetMapping("/{id}")
	public OperationAudit.Item detail(@AuthenticationPrincipal Actor actor, HttpServletRequest request,
			@PathVariable long id) {
		return audit.detail(actor, SecurityConfiguration.trace(request), id);
	}
}
