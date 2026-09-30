package com.lrj.commerce.app.application.member;

import com.lrj.commerce.member.behavior.api.MemberBehaviorApi;
import com.lrj.commerce.ordering.order.api.OrderApi;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.validation.Inputs;
import com.lrj.commerce.runtime.command.Commands;
import org.springframework.stereotype.Service;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.MEMBER_BEHAVIOR_REBUILD;

/** 应用层编排有限投影批次；交易Owner与会员Owner各自读取权威来源，不跨域写表。 */
@Service
public class MemberBehaviorRebuildService {
    private final EmployeeAccess access;
    private final Commands commands;
    private final OrderApi orders;
    private final MemberBehaviorApi behavior;

    public MemberBehaviorRebuildService(EmployeeAccess access, Commands commands, OrderApi orders, MemberBehaviorApi behavior) {
        this.access = access; this.commands = commands; this.orders = orders; this.behavior = behavior;
    }
    /** 保持原同步游标协议，下一批必须显式重新提交并判权。 */
    public record Rebuild(String after, int limit) {}
    /** 扫描量不是会员数或新增投影数，空批次仍返回真实完成回执。 */
    public record Progress(String next, int scanned, boolean done) {}

    /** guard先于旧回执，审计与投影同事务；当前批次不依赖额外行为读取权限。 */
    public Progress rebuild(Actor actor, String key, Rebuild input) {
        var permit = access.scope(actor, MEMBER_BEHAVIOR_REBUILD);
        Inputs.require(input != null && input.limit() > 0 && input.limit() <= 50, "补建批次1至50");
        Inputs.page(input.after(), input.limit());
        Object command = permit.identity() == null ? input : new Object[] { input, permit.identity() };
        return commands.runGuarded(actor, "member.behavior.rebuild", key, command, Progress.class, () -> access.lock(permit), () -> {
            var batch = orders.behaviorSources(actor.tenantId(), input.after(), input.limit());
            for (var order : batch) behavior.projectOrder(actor.tenantId(), order.orderId(), order.createdAt());
            access.lock(permit);
            access.audit(actor, permit, "member.behavior.rebuild", key, key);
            return new Progress(batch.isEmpty() ? input.after() : batch.getLast().orderId(), batch.size(), batch.size() < input.limit());
        });
    }
}
