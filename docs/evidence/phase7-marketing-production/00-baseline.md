# P7.0 基线

| 项 | 修改前事实 |
| --- | --- |
| Git | `main` / `ddf55026bf026f96cec8793556b6559bdc749b72`，工作树干净 |
| 任务分支 | `feat/phase7-marketing-production`，从上述提交创建 |
| Java | OpenJDK 21.0.11 |
| MySQL | `dev-infra-mysql84-1`，8.4.11 |
| Flyway | 测试库 41 个迁移，当前版本 41 |
| 全量基线 | `./scripts/verify.sh` 退出码 0；应用 271 run / 0 failures / 5 configured skips；架构 3/3 |
| 浏览器基线 | Phase 6 最终 jar 的受影响场景 2/2；本阶段开始时未复跑 |

Phase 2–5 的测试仍在同一 Maven reactor 里，由基线全量运行覆盖。基线日志为本机 `.local/phase7` 之前的交互运行记录；Phase 6 权威结论见 `../phase6-marketing-platform/PHASE6_REPORT.md`。该测试库仅用于项目隔离验证，基准数据另置 `commerce_phase7_bench`。
