# Codex Progress

## 任务目标

继续商城中央授权接入。已批准完整CATALOG页面补齐；用户追加其他模块，先补能力与权限契约。员工/部门权威OA，目标先本地隔离，生产目标待定。

## 已完成

- P6完整CATALOG后端/任务接管基线31dbdcd保持。
- 新增/operations/catalog固定SSO壳，复用原商品经营组件；中央Bearer及tenant用React请求上下文隔离，不写旧控制台全局凭据。
- 393项Java验证（388通过/5可选跳过）、前端构建/Prettier、完整打包通过。
- auth仓隔离演练rehearsal-c9a502b7a77c共34项通过，包括浏览器真实编辑、跨店/跨租户、退出/401/会话过期、源撤权403与依赖503。
- 1440/390视口、桌面编辑及拒绝/不可用截图已查看；本轮不重测完整PKCE输入密码登录，复用P5。
- 41c22ae产品提交已从独立任务分支正常合并推送main，远程CI在执行；最终状态以auth仓commerce-readiness/DELIVERY_RESULT.md为准。

## 已修改文件

- frontend/src/iam/{CentralCatalog,CentralProducts,api,session}，main及shared/api。
- CentralPageController、SecurityConfiguration及AuthorizationCoverageTest。
- docs/CENTRAL_CATALOG_ENTRY.md，本进度。

## 未完成

- 远程CI收尾。
- auth仓CONTRACTS_COMMERCE_EXPANSION.md草案尚待业务边界/高风险审批选择；新模块未接管。
- 真实OA映射/Owner签字、生产目标/SLO/RTO/RPO、发布观察及旧写入收缩未完成。

## 当前问题

- 真实选中旧账号凭据已到期，不能自动续期；本轮读改使用独立有限时正向夹具。
- 门店入口为编号/深链，没有CATALOG隐含授权门店目录查询。
- 原8602运行商城未切换/重启；OA用户改动保持；私有演练资源已停止但数据/证据保留。
- 无新worktree；auth/.local/p0-baselines/commerce历史基线保留。

## 下一步建议

1. 查看auth仓commerce-readiness/DELIVERY_RESULT.md确认CI实际结果。
2. 按用户业务选择定稿扩展契约，再实现逐能力接管，禁止把旧ADMIN整体映成中央管理员。
3. 真实生产迁移仍需明确映射、目标和接受条件；不自动清理私有资源。

## 恢复 Prompt

读取CODEX_PROGRESS.md及auth仓docs/implementation/oa-auth/commerce-readiness/EXECUTION_PLAN.md，从未完成的CI/扩展契约继续。保留原商城和OA改动，不重复P7容量验收。
