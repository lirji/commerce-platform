# Phase 6 基线

- 来源提交：`origin/main` `df955f1`；当前任务分支 `feat/phase6-marketing-platform` 从该提交创建，原工作树干净。
- Phase 5 报告：`PHASE_5_COMPLETE_WITH_LIMITATIONS`。已证明本地 MySQL 订单/支付/权益一致性、额度竞争、Outbox/Inbox 重投递与双实例行为；四个浏览器失败为此前已知基线，外部支付/WMS 及礼包原子语义未认证。
- 2026-09-27 本地执行 `./scripts/verify.sh`，退出码 0、`BUILD SUCCESS`，总计 02:23。`commerce-app` 264 run、0 failure、0 error、5 个按配置跳过；`architecture-tests` 3 run、0 failure。原始日志在本机 `/tmp/phase6-baseline-verify.log`，提交与结果摘要留在本文件。
- 基线没有运行浏览器或完整打包；这些是最终验收门槛，不从 Phase 5 的历史运行推断本次通过。
