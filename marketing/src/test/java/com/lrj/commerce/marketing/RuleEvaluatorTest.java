package com.lrj.commerce.marketing;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.marketing.api.Condition;
import com.lrj.commerce.marketing.api.Fact;
import com.lrj.commerce.marketing.domain.RuleEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static com.lrj.commerce.marketing.domain.RuleEvaluator.Outcome.*;

class RuleEvaluatorTest {
    private final RuleEvaluator evaluator = new RuleEvaluator();
    private final Condition yes = new Condition.Compare("level", Condition.Operator.GTE, new Fact.Decimal(BigDecimal.TEN));

    @Test void missingAndWrongTypedFactsRemainUnknownUnderNegation() {
        assertEquals(UNKNOWN, evaluator.evaluate(yes, Map.of()));
        assertEquals(UNKNOWN, evaluator.evaluate(new Condition.Not(yes), Map.of()));
        assertEquals(UNKNOWN, evaluator.evaluate(yes, Map.of("level", new Fact.Text("10"))));
    }

    @ParameterizedTest
    @CsvSource({"MATCH,MATCH,MATCH,MATCH", "MATCH,NO_MATCH,NO_MATCH,MATCH", "MATCH,UNKNOWN,UNKNOWN,MATCH",
        "NO_MATCH,MATCH,NO_MATCH,MATCH", "NO_MATCH,NO_MATCH,NO_MATCH,NO_MATCH", "NO_MATCH,UNKNOWN,NO_MATCH,UNKNOWN",
        "UNKNOWN,MATCH,UNKNOWN,MATCH", "UNKNOWN,NO_MATCH,NO_MATCH,UNKNOWN", "UNKNOWN,UNKNOWN,UNKNOWN,UNKNOWN"})
    void completeThreeValueTruthTable(String a, String b, String all, String any) {
        var first = comparison("a"); var second = comparison("b");
        var facts = new java.util.HashMap<String, Fact>();
        if (!a.equals("UNKNOWN")) facts.put("a", new Fact.Text(a));
        if (!b.equals("UNKNOWN")) facts.put("b", new Fact.Text(b));
        assertEquals(RuleEvaluator.Outcome.valueOf(all), evaluator.evaluate(new Condition.All(List.of(first, second)), facts));
        assertEquals(RuleEvaluator.Outcome.valueOf(any), evaluator.evaluate(new Condition.Any(List.of(first, second)), facts));
    }

    @Test void exactNumericComparisonAndAllOperators() {
        for (var op : Condition.Operator.values()) {
            var c = new Condition.Compare("n", op, new Fact.Decimal(new BigDecimal("10.0")));
            var result = evaluator.evaluate(c, Map.of("n", new Fact.Decimal(new BigDecimal("10.00"))));
            assertEquals(op == Condition.Operator.GT || op == Condition.Operator.LT ? NO_MATCH : MATCH, result);
        }
        assertEquals(NO_MATCH, evaluator.evaluate(yes, Map.of("level", new Fact.Decimal(BigDecimal.ONE))));
    }

    @Test void validatesWholeTreeBeforeShortCircuitAndBoundsDepth() {
        Condition tree = yes;
        for (int i = 0; i < 8; i++) tree = new Condition.Not(tree);
        final Condition deep = tree;
        assertThrows(DomainException.class, () -> evaluator.evaluate(deep, Map.of()));
        Condition wide = new Condition.All(java.util.Collections.nCopies(16,
            new Condition.All(java.util.Collections.nCopies(16, yes))));
        assertThrows(DomainException.class, () -> evaluator.evaluate(new Condition.Any(List.of(yes, wide)),
            Map.of("level", new Fact.Decimal(BigDecimal.TEN))));
    }

    @Test void rejectsUnboundedAndUnsupportedInputs() {
        assertThrows(DomainException.class, () -> new Fact.Decimal(new BigDecimal("1E+100000")));
        assertThrows(DomainException.class, () -> new Fact.Text("x".repeat(257)));
        assertThrows(DomainException.class, () -> new Condition.Compare("x", Condition.Operator.GT, new Fact.Text("a")));
        assertThrows(DomainException.class, () -> new Condition.All(List.of()));
        var facts = new java.util.HashMap<String, Fact>();
        for (int i = 0; i < 65; i++) facts.put("k" + i, new Fact.Text("x"));
        assertThrows(DomainException.class, () -> evaluator.evaluate(yes, facts));
    }

    private Condition comparison(String key) {
        return new Condition.Compare(key, Condition.Operator.EQ, new Fact.Text("MATCH"));
    }
    @org.junit.jupiter.api.Test void trustedEligibilityLiteralPreservesUnknownUnderNot() {
        var evaluator=new com.lrj.commerce.marketing.domain.RuleEvaluator();
        for(var truth:com.lrj.commerce.marketing.api.Condition.Truth.values()) {
            var node=new com.lrj.commerce.marketing.api.Condition.Literal(truth);
            org.junit.jupiter.api.Assertions.assertEquals(truth.name(),evaluator.evaluate(node,java.util.Map.of()).name());
        }
        org.junit.jupiter.api.Assertions.assertEquals(com.lrj.commerce.marketing.domain.RuleEvaluator.Outcome.UNKNOWN,
            evaluator.evaluate(new com.lrj.commerce.marketing.api.Condition.Not(new com.lrj.commerce.marketing.api.Condition.Literal(com.lrj.commerce.marketing.api.Condition.Truth.UNKNOWN)),java.util.Map.of()));
    }
}
