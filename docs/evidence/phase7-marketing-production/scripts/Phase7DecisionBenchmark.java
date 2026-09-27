import com.lrj.commerce.kernel.Money;
import com.lrj.commerce.marketing.api.Condition;
import com.lrj.commerce.marketing.api.Fact;
import com.lrj.commerce.marketing.api.DecisionModels.Selection;
import com.lrj.commerce.marketing.domain.CampaignConflictResolver;
import com.lrj.commerce.marketing.domain.RuleEvaluator;
import java.util.*;

/** 实际纯规则器与冲突器的独立微基准；不把微秒吞吐当 HTTP 平台容量。 */
class Phase7DecisionBenchmark {
    private static volatile Object sink;

    public static void main(String[] args) {
        var evaluator = new RuleEvaluator();
        var facts = Map.<String, Fact>of("memberLevel", new Fact.Text("VIP"), "orderAmount",
            new Fact.Decimal(new java.math.BigDecimal("100")));
        var leaf = new Condition.Compare("memberLevel", Condition.Operator.EQ, new Fact.Text("VIP"));
        measure("rule_1_node", () -> evaluator.evaluate(leaf, facts));
        var branches = new ArrayList<Condition>();
        for (int n = 0; n < 4; n++) {
            var leaves = new ArrayList<Condition>();
            for (int m = 0; m < 16; m++)
                leaves.add(new Condition.Compare("orderAmount", Condition.Operator.GTE,
                    new Fact.Decimal(new java.math.BigDecimal(n * 16 + m))));
            branches.add(new Condition.All(leaves));
        }
        var condition = new Condition.All(branches); // 69 节点，包含完整资源边界验证。
        measure("rule_69_nodes", () -> evaluator.evaluate(condition, facts));
        var resolver = new CampaignConflictResolver();
        var eligible = new ArrayList<CampaignConflictResolver.Eligible>();
        for (int n = 79; n >= 0; n--)
            eligible.add(new CampaignConflictResolver.Eligible(new Selection("c" + n, 1), Money.minor(100), List.of()));
        measure("select_80_eligible", () -> resolver.bestOf(eligible));
    }

    static void measure(String stage, java.util.function.Supplier<Object> operation) {
        for (int n = 0; n < 10000; n++) sink = operation.get();
        long[] samples = new long[10000];
        long started = System.nanoTime();
        for (int n = 0; n < samples.length; n++) {
            long at = System.nanoTime();
            sink = operation.get();
            samples[n] = System.nanoTime() - at;
        }
        long elapsed = System.nanoTime() - started;
        Arrays.sort(samples);
        System.out.printf(Locale.ROOT, "%s,10000,%.3f,%.3f,%.3f,%.1f%n", stage,
            samples[5000]/1000.0, samples[9500]/1000.0, samples[9900]/1000.0, 1e13/elapsed);
    }
}
