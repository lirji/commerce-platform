# Codex Progress

## 任务目标

继续商城中央授权接入。已批准完整CATALOG页面补齐；用户追加其他模块，先补能力与权限契约。员工/部门权威OA，目标先本地隔离，生产目标待定。

## 已完成

- P6完整CATALOG后端/任务接管基线31dbdcd保持。
- 新增/operations/catalog固定SSO壳，复用原商品经营组件；中央Bearer及tenant用React请求上下文隔离，不写旧控制台全局凭据。
- 393项Java验证（388通过/5可选跳过）、前端构建/Prettier、完整打包通过。
- auth仓隔离演练rehearsal-c9a502b7a77c共34项通过，包括浏览器真实编辑、跨店/跨租户、退出/401/会话过期、源撤权403与依赖503。
- 1440/390视口、桌面编辑及拒绝/不可用截图已查看；补充90089b33648d已通过真实密码+PKCE完整登录，组织/门店回跳与授权码清除通过。
- 41c22ae产品提交已从独立任务分支正常合并推送main，对应4b12706的远程CI36660768385已SUCCESS；最终工具补充状态以auth仓commerce-readiness/DELIVERY_RESULT.md为准。

## 已修改文件

- frontend/src/iam/{CentralCatalog,CentralProducts,api,session}，main及shared/api。
- CentralPageController、SecurityConfiguration及AuthorizationCoverageTest。
- docs/CENTRAL_CATALOG_ENTRY.md，本进度。

## 未完成

- 本轮产品CI已通过；最新纯文档提交与工具追加CI以auth仓交付记录为准。
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

## 2026-09-29 CE-03-I库存后端

用户已明确采用员工运营端边界、高风险独立权限并沿用现有审批；不再等待业务选择。auth CE-02 main1b1ee01/CI36663171698 SUCCESS。

本仓feat/central-inventory-access实现库存read/receive独立中央能力、OPERATOR执行引用、V49分族路由、V50同事务身份审计；Owner事实锁与Commands.runGuarded防去头回退/旧回执/跨代际。完整397项392通过5可选跳过、hygiene无阻断；auth完整51跨进程验收e068a97301ff通过。详细变更、失败历史及操作边界见docs/CENTRAL_INVENTORY_ACCESS.md及auth仓CE03_INVENTORY.md。

私有新MySQL commerce-inventory-mysql-7841e58190端口43308，.local/central-inventory/owned.env。共享测试库V49失败已限定修复并原样应用49/50，保留employee_authority_route_failed_ce03_20260929空表及本地repair证据；不改成功迁移checksum。原commerce_local/8602运行未切换。

待完成：Git提交合并推送和CI结果；CE-03-U库存员工UI、CE-03-D商家门店、CE-04—08后续模块。真实身份映射/Owner/有效期与生产目标仍未确定；客户与平台身份未接管。未新增worktree、未清理任何保留数据。
