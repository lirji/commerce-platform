import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;

/** 隔离 MySQL 的单连接查询微基准；数字只代表本地机器，不是生产容量承诺。 */
public final class Phase7JdbcBenchmark {
	private static final String CANDIDATES = "SELECT campaign_id,version,store_id,merchant_id,name,"
			+ "valid_from,valid_to,minimum_spend,discount_amount,rule_json,status,lock_version,policy_json "
			+ "FROM marketing_campaign WHERE tenant_id=? AND store_id='store1' AND status='PUBLISHED' "
			+ "AND valid_from<=? AND valid_to>? ORDER BY campaign_id LIMIT 101";
	private static final String AUDIENCE = "SELECT EXISTS(SELECT 1 FROM marketing_audience_member "
			+ "WHERE tenant_id=? AND audience_id='sample' AND version=1 AND member_id='m000010')";
	private static final String EXECUTIONS = "SELECT order_id,campaign_id,campaign_version,quote_id,member_id,"
			+ "store_id,rule_id,rule_version,audience_id,audience_version,benefit_id,benefit_version,grant_id,"
			+ "discount_amount,reason_code,status,evaluated_at,created_at,updated_at,lock_version "
			+ "FROM marketing_execution WHERE tenant_id=? AND order_id>? "
			+ "ORDER BY order_id,campaign_id LIMIT 50";

	private Phase7JdbcBenchmark() {
	}

	public static void main(String[] args) throws Exception {
		String original = System.getenv("COMMERCE_TEST_DB_URL");
		if (original == null || !original.contains("/commerce_test_20260923?"))
			throw new IllegalStateException("必须先加载项目隔离测试库环境");
		String url = original.replace("/commerce_test_20260923?", "/commerce_phase7_bench?");
		try (Connection db = DriverManager.getConnection(url, System.getenv("COMMERCE_DB_USER"),
				System.getenv("COMMERCE_DB_PASSWORD"))) {
			System.out.println("stage,tier,rows,p50_ms,p95_ms,p99_ms,throughput_per_second");
			for (String tier : new String[] { "small", "medium", "large" }) {
				String tenant = "phase7-" + tier;
				measure(db, "candidate_discovery", tier, CANDIDATES, tenant, true, null);
				measure(db, "audience_membership", tier, AUDIENCE, tenant, false, null);
				String cursor = switch (tier) {
					case "small" -> "o00000500";
					case "medium" -> "o00005000";
					default -> "o00030000";
				};
				measure(db, "execution_page", tier, EXECUTIONS, tenant, false, cursor);
			}
			measure(db, "audience_50000_storage_probe", "large",
					"SELECT EXISTS(SELECT 1 FROM marketing_audience_member WHERE tenant_id=? "
							+ "AND audience_id='storage-probe' AND version=1 AND member_id='probe030000')",
					"phase7-large", false, null);
		}
	}

	private static void measure(Connection db, String stage, String tier, String sql, String tenant,
			boolean time, String cursor) throws Exception {
		try (PreparedStatement query = db.prepareStatement(sql)) {
			query.setString(1, tenant);
			if (time) {
				query.setString(2, "2026-09-27 00:00:00");
				query.setString(3, "2026-09-27 00:00:00");
			}
			else if (cursor != null)
				query.setString(2, cursor);
			for (int i = 0; i < 100; i++)
				consume(query);
			long[] nanos = new long[500];
			int rows = 0;
			long started = System.nanoTime();
			for (int i = 0; i < nanos.length; i++) {
				long at = System.nanoTime();
				rows = consume(query);
				nanos[i] = System.nanoTime() - at;
			}
			long total = System.nanoTime() - started;
			Arrays.sort(nanos);
			System.out.printf(java.util.Locale.ROOT, "%s,%s,%d,%.3f,%.3f,%.3f,%.1f%n", stage, tier, rows,
					nanos[249] / 1e6, nanos[474] / 1e6, nanos[494] / 1e6, nanos.length * 1e9 / total);
		}
	}

	private static int consume(PreparedStatement query) throws Exception {
		int count = 0;
		try (ResultSet results = query.executeQuery()) {
			while (results.next()) {
				results.getObject(1);
				count++;
			}
		}
		return count;
	}
}
