package com.lrj.commerce.app.http.marketing.journey;

import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;

/** 资格只表达独立集合能力；GET不生成长期来源、不执行命令、不代替已有目标Facts。 */
@RestController
public class JourneyAccessController {
    private final EmployeeAccess access;
    public JourneyAccessController(EmployeeAccess access){this.access=access;}
    record ActionAccess(boolean allowed) {}
    private static final Map<String,EmployeeAccess.Capability> HINTS=Map.ofEntries(
        Map.entry("/v1/operations/journeys/create-access",JOURNEY_CREATE),Map.entry("/v1/operations/journeys/validate-access",JOURNEY_VALIDATE),
        Map.entry("/v1/operations/journeys/preview-access",JOURNEY_PREVIEW),Map.entry("/v1/operations/journeys/submit-access",JOURNEY_SUBMIT),
        Map.entry("/v1/operations/journeys/approve-access",JOURNEY_APPROVE),Map.entry("/v1/operations/journeys/reject-access",JOURNEY_REJECT),
        Map.entry("/v1/operations/journeys/publish-access",JOURNEY_PUBLISH),Map.entry("/v1/operations/journeys/pause-access",JOURNEY_PAUSE),
        Map.entry("/v1/operations/journeys/pump-access",JOURNEY_PUMP),Map.entry("/v1/operations/journey-instances/create-access",JOURNEY_INSTANCE_CREATE),
        Map.entry("/v1/operations/journey-instances/control-access",JOURNEY_INSTANCE_CONTROL),Map.entry("/v1/operations/journey-scans/retry-access",JOURNEY_SCAN_RETRY),
        Map.entry("/v1/operations/marketing-effects/rebuild-access",MARKETING_EFFECT_REBUILD));
    @GetMapping({"/v1/operations/journeys/create-access","/v1/operations/journeys/validate-access","/v1/operations/journeys/preview-access","/v1/operations/journeys/submit-access","/v1/operations/journeys/approve-access","/v1/operations/journeys/reject-access","/v1/operations/journeys/publish-access","/v1/operations/journeys/pause-access","/v1/operations/journeys/pump-access","/v1/operations/journey-instances/create-access","/v1/operations/journey-instances/control-access","/v1/operations/journey-scans/retry-access","/v1/operations/marketing-effects/rebuild-access"})
    public ActionAccess qualification(@AuthenticationPrincipal Actor actor,HttpServletRequest request) {
        var permit=access.scope(actor,HINTS.get(request.getRequestURI()));
        EmployeeAccess.requireSame(permit,access.scope(actor,permit.capability()));return new ActionAccess(true);
    }
}
