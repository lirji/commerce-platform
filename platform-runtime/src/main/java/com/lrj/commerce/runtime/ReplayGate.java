package com.lrj.commerce.runtime;
import com.lrj.commerce.runtime.api.EventHandler;
import com.lrj.commerce.runtime.api.EventHandler.SideEffect;
import java.util.*;

/**
 * 重放安全门：执行任何历史重放前，根据消费者声明的副作用分类证明安全，无法证明即拒绝（失败即关闭）。
 * 硬规则不能被声明覆盖：资金、外部与不可逆副作用一律拒绝；未分类拒绝；重新执行已处理事件只允许纯投影。
 * 创建任务与每一项执行前都会校验，部署改变分类后正在运行的任务也会停止。
 */
public final class ReplayGate {
    private ReplayGate() { }
    /** UNPROCESSED只执行该消费者尚无Inbox的事件（Inbox去重）；REPROCESS对已处理事件也重新执行（只允许纯投影）。 */
    public enum Mode { UNPROCESSED, REPROCESS }
    public record Decision(boolean allowed,String code,String detail) {
        static Decision allow(String detail){return new Decision(true,"ALLOWED",detail);}
        static Decision deny(String code,String detail){return new Decision(false,code,detail);}
    }
    static final Set<SideEffect> NEVER=EnumSet.of(SideEffect.FINANCIAL_SIDE_EFFECT,SideEffect.EXTERNAL_SIDE_EFFECT,SideEffect.IRREVERSIBLE_SIDE_EFFECT);
    public static Decision check(EventHandler handler,Mode mode) {
        if(handler==null)return Decision.deny("UNKNOWN_CONSUMER","消费者不存在");
        if(mode==null)return Decision.deny("INVALID_MODE","重放模式缺失");
        var safety=handler.replaySafety();
        if(safety==null||safety.effects().isEmpty())return Decision.deny("UNCLASSIFIED","消费者未声明副作用分类");
        for(var effect:safety.effects())if(NEVER.contains(effect))return Decision.deny("REPLAY_NOT_SUPPORTED",effect+"："+safety.evidence());
        if(!safety.historicalReplay())return Decision.deny("REPLAY_NOT_SUPPORTED",safety.effects()+"："+safety.evidence());
        if(mode==Mode.REPROCESS&&!(safety.reprocess()&&safety.effects().equals(EnumSet.of(SideEffect.PURE))))return Decision.deny("REPROCESS_NOT_SUPPORTED","只有纯投影可以重新执行已处理事件："+safety.evidence());
        return Decision.allow(safety.effects()+"："+safety.evidence());
    }
}
