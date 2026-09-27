# Phase 7 复跑说明

所有容量脚本只针对 `commerce_phase7_bench` 和 `phase7-small/medium/large` 合成租户；不执行 DELETE、TRUNCATE、重置或共享容器重启。当前库、私有令牌和 OLD detached worktree 已留在 `.local`，便于本机复跑。数据库/工作树创建、授权和 migration 的原始运行记录为本次本地环境证据，不自动授权操作其他环境。

## 已存在本地数据复跑

1. `source .local/runtime.env`，将 app 的 DB URL 从 `commerce_test_20260923` 替换为 `commerce_phase7_bench`，启动最终 jar 到 8623/8624，workers=false、sandbox=true、coupon-enabled=true；仅 NEW 的完整解释测量可 extended-trace-enabled=true（混合期必须 false）；方法采样需 profiling-enabled=true。不要打印环境或凭据。
2. `python3 .../bootstrap_bench.py`（已有私有 bootstrap 时直接复用）。
3. `quote_load.py` 为每层真实 API 120/8 样本。`contention_load.py --provider credit` 或 `coupon` 为双进程 120 用户/30 额度，生成独立新 store/配置；以 GRANTED/最终额度判断完成，不以 pump=0 判断排空。
4. `quote_scenarios.py` 创建独立 STANDARD 成员和单命中活动，执行无命中/单命中/人群 miss/三租户情景；须先有 contention 券测试店铺。它会增加 LARGE 的当前候选，不能在此之后假装仍是最初 80 候选数据层。
5. `query_count.py` 用 benchmark schema 的归一 performance_schema digest 测量实际 quote 查询数，创建 20 人群/69 节点配置；CLI 的元信息查询与应用查询区分。无其他 benchmark 流量时运行。
6. `query_plans.sql` 通过已有 dev_infra MySQL root 会话在专用库执行，时间统一 UTC；UPDATE 仅 EXPLAIN。JDBC 微基准需项目 MySQL driver、`COMMERCE_DB_USER/PASSWORD/TEST_DB_URL`，class 输出到 `.local/phase7-bench/classes`。
7. `Phase7DecisionBenchmark.java` 使用 `marketing/target/classes:shared-kernel/target/classes`，分别测纯规则与选择；不能把它替代 HTTP 负载。
8. `browser-affected.sh` 自动启动最终 jar、在项目隔离测试库的独立租户执行两项真实浏览器场景并停止自己的进程，结果 `.local/phase7-browser/`。

## 新的空 benchmark schema

由操作者在隔离环境明确创建专用库并授权项目用户，使用项目全部 Flyway migration 初始化到 V43；不要覆盖现有库。先启动 NEW jar、运行 `bootstrap_bench.py`，再将 `generate_scale_sql.py` 和 `generate_history_sql.py` 输出分别导入该专用库。这两个生成器是一次性 INSERT 工具，重复导入会唯一键失败；不会清空数据。生成的初始数据时间为本阶段模型（2026-09），未来复跑应在新合成库重新选择时间窗口，不修改正式已发布业务数据。

MySQL CLI 示例需使用容器环境中的 root 密码变量，不能复制明文：

```bash
python3 docs/evidence/phase7-marketing-production/scripts/generate_scale_sql.py > .local/phase7-scale.sql
docker exec -i dev-infra-mysql84-1 sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot commerce_phase7_bench' < .local/phase7-scale.sql
```

本机二进制兼容需保留 OLD df955f1 jar 与 NEW jar、相同 schema、workers=false，逐个受控泵送并查询事件/Inbox 和业务状态；开启 COUPON 后 OLD 会丢失赠券承诺的负例仅能在隔离库运行，不接用户流量。
