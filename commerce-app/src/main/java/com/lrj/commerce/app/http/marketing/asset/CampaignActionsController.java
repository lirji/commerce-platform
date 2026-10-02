package com.lrj.commerce.app.http.marketing.asset;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 每项活动资格独立核验；提示不代替实际内容版本的Owner判权或状态检查。 */
@RestController
@ConditionalOnProperty(name = {"commerce.iam.store-read.enabled", "commerce.iam.employee.enabled"}, havingValue = "true")
public class CampaignActionsController {
    private final EmployeeAccess access;
    public CampaignActionsController(EmployeeAccess access) { this.access = access; }

    /** 仅核对create完整租户资格，不隐含活动读取或其他动作能力。 */
    @GetMapping("/v1/operations/campaigns/create-access")
    public ActionAccess create(@AuthenticationPrincipal Actor actor) {
        return qualification(actor, EmployeeAccess.Capability.CAMPAIGN_CREATE);
    }

    /** 仅核对preview完整租户资格，不隐含活动读取或其他动作能力。 */
    @GetMapping("/v1/operations/campaigns/preview-access")
    public ActionAccess preview(@AuthenticationPrincipal Actor actor) {
        return qualification(actor, EmployeeAccess.Capability.CAMPAIGN_PREVIEW);
    }

    /** 仅核对submit完整租户资格，不隐含活动读取或其他动作能力。 */
    @GetMapping("/v1/operations/campaigns/submit-access")
    public ActionAccess submit(@AuthenticationPrincipal Actor actor) {
        return qualification(actor, EmployeeAccess.Capability.CAMPAIGN_SUBMIT);
    }

    /** 仅核对approve完整租户资格，不隐含活动读取或其他动作能力。 */
    @GetMapping("/v1/operations/campaigns/approve-access")
    public ActionAccess approve(@AuthenticationPrincipal Actor actor) {
        return qualification(actor, EmployeeAccess.Capability.CAMPAIGN_APPROVE);
    }

    /** 仅核对reject完整租户资格，不隐含活动读取或其他动作能力。 */
    @GetMapping("/v1/operations/campaigns/reject-access")
    public ActionAccess reject(@AuthenticationPrincipal Actor actor) {
        return qualification(actor, EmployeeAccess.Capability.CAMPAIGN_REJECT);
    }

    /** 仅核对publish完整租户资格，不隐含活动读取或其他动作能力。 */
    @GetMapping("/v1/operations/campaigns/publish-access")
    public ActionAccess publish(@AuthenticationPrincipal Actor actor) {
        return qualification(actor, EmployeeAccess.Capability.CAMPAIGN_PUBLISH);
    }

    /** 仅核对pause完整租户资格，不隐含活动读取或其他动作能力。 */
    @GetMapping("/v1/operations/campaigns/pause-access")
    public ActionAccess pause(@AuthenticationPrincipal Actor actor) {
        return qualification(actor, EmployeeAccess.Capability.CAMPAIGN_PAUSE);
    }

    /** 返回前再次核对身份与授权路径，不能把首次资格快照当作持久许可。 */
    private ActionAccess qualification(Actor actor, EmployeeAccess.Capability capability) {
        if (actor == null || actor.role() != Actor.Role.OPERATOR || actor.executionId() == null)
            throw new DomainException(DomainException.Code.FORBIDDEN, "中央活动授权拒绝");
        var before = access.scope(actor, capability);
        EmployeeAccess.requireSame(before, access.scope(actor, capability));
        return new ActionAccess(true);
    }

    /** 没有业务数据或授权凭据的资格提示。 */
    public record ActionAccess(boolean allowed) {}
}
