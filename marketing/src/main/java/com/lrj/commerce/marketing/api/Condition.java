package com.lrj.commerce.marketing.api;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.kernel.Identifiers;
import java.util.List;

/** 运营规则的有限语法树，不包含网络、数据库或通用表达式求值。 */
public sealed interface Condition permits Condition.Compare, Condition.All, Condition.Any, Condition.Not, Condition.Literal {
    enum Truth { MATCH, NO_MATCH, UNKNOWN }
    /** 仅可信运行时组合人群资格；运营JSON不开放此节点。 */
    record Literal(Truth value) implements Condition {
        public Literal { if(value==null)throw new DomainException(DomainException.Code.INVALID_INPUT,"资格结果不能为空"); }
    }
    enum Operator { EQ, GT, GTE, LT, LTE }

    record Compare(String field, Operator operator, Fact expected) implements Condition {
        public Compare {
            Identifiers.require(field);
            if (operator == null || expected == null || (expected instanceof Fact.Text && operator != Operator.EQ)) {
                throw new DomainException(DomainException.Code.INVALID_INPUT, "比较类型或操作符无效");
            }
        }
    }
    record All(List<Condition> children) implements Condition {
        public All { children = copy(children); }
    }
    record Any(List<Condition> children) implements Condition {
        public Any { children = copy(children); }
    }
    record Not(Condition child) implements Condition {
        public Not {
            if (child == null) throw new DomainException(DomainException.Code.INVALID_INPUT, "条件不能为空");
        }
    }

    private static List<Condition> copy(List<Condition> children) {
        if (children == null || children.isEmpty() || children.size() > 16 || children.stream().anyMatch(java.util.Objects::isNull)) {
            throw new DomainException(DomainException.Code.INVALID_INPUT, "组合条件数量必须为1至16");
        }
        return List.copyOf(children);
    }

    /** 先以有界遍历验证整个树，不能因短路求值绕过后续节点的资源限制。 */
    static void validate(Condition condition) {
        if (condition == null) throw new DomainException(DomainException.Code.INVALID_INPUT, "规则不能为空");
        record Entry(Condition node, int depth) { }
        var pending = new java.util.ArrayDeque<Entry>();
        pending.push(new Entry(condition, 1));
        int nodes = 0;
        while (!pending.isEmpty()) {
            var entry = pending.pop();
            if (++nodes > 128 || entry.depth() > 8) {
                throw new DomainException(DomainException.Code.LIMIT_EXCEEDED, "规则节点或深度超限");
            }
            List<Condition> children = switch (entry.node()) {
                case All all -> all.children();
                case Any any -> any.children();
                case Not not -> List.of(not.child());
                case Compare ignored -> List.of();
                case Literal ignored -> List.of();
            };
            for (Condition child : children) pending.push(new Entry(child, entry.depth() + 1));
        }
    }
}
