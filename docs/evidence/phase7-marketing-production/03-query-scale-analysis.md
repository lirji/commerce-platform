# 查询与索引证据

在 LARGE 层 4,000 发布活动、80 当前有效活动上执行 MySQL `EXPLAIN ANALYZE`：

| 查询 | 改动前 | 改动后 |
| --- | --- | --- |
| 活动候选 | `ORDER BY campaign_id LIMIT 101` 从 PRIMARY 读取前 101 行，0.171 ms，因过期行触发 100 上限；若只加时间过滤，则 PRIMARY 扫 4,000 行、1.72 ms | 加 `valid_from<=now AND valid_to>now` 与 `ix_campaign_candidate_window(tenant,store,status,valid_to,valid_from,campaign)`，索引范围扫描 80 行再对 80 行排序，0.165 ms |
| 固定人群点查 | 复合主键 `(tenant,audience,version,member)`，一行点查 | 保持；未新增索引 |
| 执行历史单页 | 主键 `(tenant,order,campaign)` 区间扫描 50 行，0.0435 ms | 保持；稳定游标和页限额 1–100 |

候选上限仍是 100 个**同时有效**活动的产品/运行保护；不会只把限制抬高。活动 JSON 仍需读取有效候选，规则器的数据库访问为事实准备一次与人群批量查询一次，不在规则节点循环访问 Mapper。新增集成回归用 101 个过期已发布行证明其不会挤占有效候选。没有证据支持为人群点查和执行列表再加索引。

## 扩展历史查询计划

LARGE：执行 50,000、grant 50,000、DELIVERED event/Inbox 各 50,000；存储探针人群 50,000。实际原始计划见 `results/query-plans.md`，可复跑 `scripts/query_plans.sql`（额度 UPDATE 仅 EXPLAIN，不修改数据）。

| 查询 | 实际计划/读行 | 本次观察 ms |
| --- | --- | ---: |
| 活动候选 | 新有效期索引范围 80 行，排序 80，LIMIT 101 | 0.231 |
| 50k 人群成员资格 | 复合主键常量点查 1 行，执行前读取 | <0.001（不含网络） |
| 50k 执行游标单页 | PRIMARY 范围仅读 50 行 | 0.119 |
| 50k grant 单笔 | PRIMARY 1 行常量点查 | <0.001（不含网络） |
| 50k grant 会员钱包游标 | `ix_entitlement_wallet` 范围 40 行，LIMIT 50，无大排序 | 2.080 |
| 50k event 游标 | 本层 PRIMARY 范围返回 50 行；小租户计划走已有 `ix_event_replay`，读 48 行再小排序 | 0.045 / 0.217 |
| Inbox 唯一消费查找 | `(consumer_id,event_id)` 主键 1 行 | <0.001（不含网络） |
| 发布版本组 | `(tenant,campaign)` PRIMARY、LIMIT 51，当前样本 1 版本 | 0.024 |
| 券定义/额度条件 | PRIMARY 点查 1 行；UPDATE EXPLAIN 为 PRIMARY range，估计 1 行 | 未执行 UPDATE ANALYZE；锁耗时见订单采样 |

MySQL EXPLAIN 常量点查在优化阶段提前取行，因此极小的 reported time 不能当客户端延迟；实际 JDBC 存储探针 P50/P95/P99 0.360/0.653/1.301 ms，500 样本。历史数据分布仍是合成数据，不覆盖每种跨租户 ID 分布，查询计划随统计变化；没有证据支持新增历史索引。分页上限 1–100、固定排序和游标保留；发布版本组本轮没有 50 个版本的容量承诺。

## N+1 实测

在实际 HTTP quote 前后读取 `performance_schema.events_statements_summary_by_digest`，只统计 benchmark schema 的归一 SELECT。每种 20 请求：10 候选、81 候选、1 人群引用、20 不同人群引用且每活动 69 节点；分别均为 321 SELECT（320 应用查询 + 1 次 MySQL CLI 元信息查询）。候选查询始终 20 次；有人群时批量 membership 查询始终 20 次，无人群 0 次。规则节点、候选与人群引用的增长没有增加每次 quote 的 repository 查询数（16 次）。

归一 digest 不含令牌和值参数。实际脚本 `scripts/query_count.py`；该脚本会在专用库创建合成店铺、配置和快照，不在产品数据库执行。
