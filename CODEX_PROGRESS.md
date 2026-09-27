# Codex Progress

## 任务目标

执行用户提供的 Phase 7 文档，验证营销平台的生产规模模型、旧/新滚动兼容、第二既有权益、确定性活动冲突及运维可恢复性。

## 当前状态

`PHASE_7_COMPLETE_WITH_LIMITATIONS`。任务分支 `feat/phase7-marketing-production`，基线 main `ddf55026bf026f96cec8793556b6559bdc749b72`。权威报告：`docs/evidence/phase7-marketing-production/PHASE7_REPORT.md`；状态与计划：`docs/delivery/phase7-marketing-production/`。

Phase 7 原实施轮次未 commit/push。用户现明确要求「先把阶段7的代码提交并推送到远程main分支，再开启阶段8」，授权正常提交、合并和推送 origin/main。本轮两笔代码提交 `11c7f37`、`a6b3b8d` 和验收 `40376b3` 已正常合并并推送 origin/main；远程实测 SHA `40376b3741a9723377810c69d8dbda972e79f018`，不包含生产部署。

## 已完成

- 基线 verify：应用 271/0/5 configured skips、架构 3/3，V41。
- V42 有效期索引/SQL；历史过期发布活动不再占当前候选限额，仍保留 100 同时有效候选。
- 显式 BEST_OF 纯解析、落选解释、竞争预览，同额按 ID，旧预览缺省语义不变。
- V43 券订单额度预留/同事务发放/释放、来源唯一与固定券 ID、执行类型；COUPON 复用原券 owner，原 CREDIT 保持。
- 默认关闭 coupon 配置与 extended-trace 门禁；真实 OLD df955f1+NEW 共享 V43 和事件，旧 CREDIT 生产链、新 completion 唯一消费已验证。
- 收尾实际发现并修复新 Trace enum 使 OLD 读 NEW quote 500；最后门禁关闭时 OLD 读/下单/取消均 200，赢家与金额不变，旧 enum 往返回归通过。
- 额外回退探针证明 OLD 会忽略 coupon 字段而丢失承诺；部署/回退文档明确只在 OLD 全退后启用，启用后回退目标必须保留券能力。
- 三层真实 quote、方法段、规则/选择微基准、三租户、无命中/单命中/人群 miss；50k 执行/grant/event/Inbox、人群存储探针及真实查询计划。
- 10→81 候选、1→20 人群、69 节点时 quote 查询数不增长；公开固定名单 API 仍 500（数据库 CHECK 100k）。
- 最终双进程每种 120 用户/30 额度，30 成功/90 409、reserved=0/issued=30/grants=30，独立批次零新增死锁/锁超时；额度/执行/订单/赠券分段有证据。
- 券最后一份竞争、取消、事务效果后回滚恢复、真实双实例唯一发放；七个新增 MySQL 场景及一项旧 enum 契约回归。
- 删除冲突 ID 破平局的 mutation 检出，finally 恢复；最终 clean 构建应用 279/0/5、架构 3/3、marketing 27/27、order 45/45、UI 入 jar。
- 最终 jar 浏览器受影响 2/2；测试等待真实异步退款记录后进入退款页面。
- 完成 00–15 证据、可复跑脚本、无密钥结果 CSV/计划、Review/QA、报告和文档同步。

## 已修改文件

完整清单：`docs/evidence/phase7-marketing-production/results/changed-files.md`。

- `benefit/` 券 API、Service、Mapper/XML
- `marketing/` 冲突解析、决策、强化确定性测试
- `marketing-runtime/` 活动契约、有效候选、预览、资金承诺与执行
- `order-runtime/` 券预留/支付/取消和可关闭性能日志
- `trade/` 可关闭 quote 分段采样
- `commerce-app/` V42/V43、默认门禁/采样配置、MySQL 测试
- `frontend/tests/commerce.spec.ts` 异步退款等待
- `docs/delivery/phase7-marketing-production/`、`docs/evidence/phase7-marketing-production/`
- `deploy/README.md`、`docs/doc-map.md`、`CODEX_PROGRESS.md`

## 未完成

本轮已授权范围内没有剩余实施/验证门禁。生产上线/长期压测、真实硬件和峰值、跨券激活点 OLD 全量回退、细角色、叠加、礼包、外部权益和客户 SLO 是后续独立工作，不宣称已完成。

## 当前问题

- 生产容量没有获批目标，本地短批次与共享 MySQL 实例结果不可当生产承诺。
- 首次最终构建两项旧旅程偶发失败未归因；独立复跑及后续两次完整 clean 都通过，历史记录未删除。
- Phase 5 完整浏览器集合未重跑，其原有四项失败仍是历史边界；本轮只认证受影响 2/2。
- OLD 忽略 coupon 字段；券或扩展 Trace 激活后的安全回退目标必须保留已启用能力/enum，不能仅排空 HELD 后切回 OLD。
- `.local/phase7-old` detached df955f1 worktree、专用 `commerce_phase7_bench` 数据库及忽略目录原始日志/令牌保留用于复跑；没有删除共享数据。所有本轮验证节点在收尾停止，共享 dev_infra 未重启。

## 下一步建议

1. Phase 7 Git 交付已完成，完成本交付结果补记提交后，从远程 main 创建 Phase 8 任务分支。
2. 下一阶段先输入真实生产峰值、租户/历史分布和 SLO，在授权环境做持续负载及滚动/恢复验收。
3. Phase 7 远程交付确认后，创建 Phase 8 任务分支，按用户提供的 phase-8-marketing-journey-workflow-orchestration.md 先做基线与 Journey 能力盘点。

## 恢复 Prompt

读取 `CODEX_PROGRESS.md` 和 Phase 7 最终报告。用户已明确授权本轮把 Phase 7 提交并推送到远程 main，再开启 Phase 8；先核对 Git 实际状态，完成未完成交付，然后接续 Phase 8。不重做 Phase 7 实现，不强推、不生产部署。
