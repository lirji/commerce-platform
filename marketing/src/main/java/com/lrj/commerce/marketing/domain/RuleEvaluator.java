package com.lrj.commerce.marketing.domain;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.kernel.Identifiers;
import com.lrj.commerce.marketing.api.Condition;
import com.lrj.commerce.marketing.api.Fact;
import java.util.Map;

/** 三值规则求值；缺失或不兼容事实不能经 NOT 变为准入资格。 */
public final class RuleEvaluator {
    public enum Outcome { MATCH, NO_MATCH, UNKNOWN }

    /** 验证资源边界后才递归求值，深度已经限制为八层。 */
    public Outcome evaluate(Condition condition, Map<String, Fact> facts) {
        Condition.validate(condition);
        if (facts == null) throw new DomainException(DomainException.Code.INVALID_INPUT, "事实不能为空");
        if (facts.size() > 64) throw new DomainException(DomainException.Code.LIMIT_EXCEEDED, "事实数量超限");
        facts.keySet().forEach(Identifiers::require);
        if (facts.values().stream().anyMatch(java.util.Objects::isNull)) {
            throw new DomainException(DomainException.Code.INVALID_INPUT, "事实值不能为空");
        }
        return visit(condition, Map.copyOf(facts));
    }

    private Outcome visit(Condition condition, Map<String, Fact> facts) {
        return switch (condition) {
            case Condition.Literal literal -> Outcome.valueOf(literal.value().name());
            case Condition.Compare comparison -> compare(comparison, facts.get(comparison.field()));
            case Condition.Not not -> switch (visit(not.child(), facts)) {
                case MATCH -> Outcome.NO_MATCH;
                case NO_MATCH -> Outcome.MATCH;
                case UNKNOWN -> Outcome.UNKNOWN;
            };
            case Condition.All all -> combine(all.children(), facts, true);
            case Condition.Any any -> combine(any.children(), facts, false);
        };
    }

    private Outcome combine(java.util.List<Condition> children, Map<String, Fact> facts, boolean all) {
        boolean unknown = false;
        for (Condition child : children) {
            Outcome result = visit(child, facts);
            if (all && result == Outcome.NO_MATCH) return Outcome.NO_MATCH;
            if (!all && result == Outcome.MATCH) return Outcome.MATCH;
            unknown |= result == Outcome.UNKNOWN;
        }
        if (unknown) return Outcome.UNKNOWN;
        return all ? Outcome.MATCH : Outcome.NO_MATCH;
    }

    private Outcome compare(Condition.Compare comparison, Fact actual) {
        if(comparison.operator()==Condition.Operator.CONTAINS){
            if(!(actual instanceof Fact.Tags tags)||!(comparison.expected() instanceof Fact.Text text))return Outcome.UNKNOWN;
            return tags.values().contains(text.value())?Outcome.MATCH:Outcome.NO_MATCH;
        }
        if (actual == null || actual.getClass() != comparison.expected().getClass()) return Outcome.UNKNOWN;
        int order = switch (actual) {
            case Fact.Decimal decimal -> decimal.value().compareTo(((Fact.Decimal) comparison.expected()).value());
            case Fact.Text text -> text.value().compareTo(((Fact.Text) comparison.expected()).value());
            case Fact.Tags ignored -> throw new IllegalStateException("标签只允许集合包含比较");
        };
        boolean matches = switch (comparison.operator()) {
            case EQ -> order == 0;
            case GT -> order > 0;
            case GTE -> order >= 0;
            case LT -> order < 0;
            case LTE -> order <= 0;
            case CONTAINS -> false; // 集合比较已经在上方处理。
        };
        return matches ? Outcome.MATCH : Outcome.NO_MATCH;
    }
}
