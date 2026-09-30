# Codex Progress

## 任务目标

连续完成已批准商城中央员工权限扩展。OA权威；会员/营销真实TENANT_ALL，库存/交易真实门店；高风险独立权限、沿用现有审批。授权任务分支、正常合并推送main，不含生产部署/清理数据。

## 已完成

- CE02、CE03库存I/U、目录D0/D1/D2已交付。D2 auth3a8f7e9 CI被后续P0取消，commerce5587d53 CI36668436980 SUCCESS；包含D2基线的auth P0 dd07223 CI36668768692 SUCCESS。
- D2验证402项397PASS/5skip；真实隔离99项PASS，目录/库存/CATALOG浏览器及1440/390截图已检查，证据CE03_DIRECTORY.md。
- CE04-P0协议DONE并已推送dd07223161df6e3dcb6e55756aac7f91369f416d；251单测、4真实PG+graph IT、Boot4/SDK/package PASS。真实Owner登记要求保留；不当作实际会员Owner验收。

## 已修改文件

- auth feat/commerce-member-ui-contract：P2契约/227入口/P6会员浏览器阶段/新governance-ce04-member.mjs。
- commerce feat/central-member-ui：CentralMembers、MemberActionsController三提示、精确路由/SSO白名单、提示与壳负例测试。P1已提交，SDK固定dd07223。

## 未完成

- CE04-P1本地DONE：406项401PASS/5skip；真实隔离rehearsal-451c622849b4共114PASS；36项Python/223入口核对/hygiene无阻断。工具显式--identity-subnet 10.254.81.0/24解决默认网络池耗尽，不删除旧网络。
- P1 auth2cb8c11/commerce1bc81f2已合并推送，CI36669783915/36669785165均SUCCESS。
- P2本地DONE：407项402PASS/5skip，最终rehearsal-aa0b96471601共137PASS（会员12条浏览器，目录/库存/CATALOG回归）。当前截图已实际查看，36项Python/227入口/两仓hygiene无阻断；正在按分支提交合并推送。失败e56215a12fb5是Select测试定位问题，修后完整复跑，不删证据。
- 成长/标签/行为/周期/积分及CE05—08仍未完成。
- 真实OA映射/Owner签字/永久授权截止/生产SLO RTO/RPO待定，仅阻塞对应动作。

## 当前问题

- P1首轮编译INVALID_ARGUMENT改为已有INVALID_INPUT。第一次完整回归：故障注入被MANDATORY代理拦截，改用AopTestUtils取得真实spy设置；库存旧测试对现已接入的会员入口应403而非401，已修正。失败日志保留，不声称通过。
- V52已在专用MySQL成功应用，不可改历史。commerce-inventory-mysql-7841e58190:43308保留运行；source .local/runtime.env后source .local/central-inventory/owned.env，不输出凭据。
- 原8602与OA脏文件不碰；无新worktree。.local私密证据、停止的演练容器/数据保留不提交不删除。
- 5秒为本地准入窗口，不宣称跨库瞬时撤权；旧二进制不认识新族，回退需认识该族版本+STOPPED。

## 下一步建议

1. P2验证已完成，Git交付并核对远程CI。P1两仓CI已SUCCESS。auth他任务782ae3d仅gitignore且已在origin/main，已保留其工作树，不清理。
2. 继续CE04-G成长政策/钱包/调整/重算；auth .local/governance/commerce-contracts/member-growth-draft.md已做源码细化草案，需转正式契约后按G0协议/G1 Owner/G2 UI实施。
3. 连续完成后续已批准片，不因一片完成停止或重复询问。

## 恢复 Prompt

读取CODEX_PROGRESS和auth docs/implementation/oa-auth/commerce-readiness/EXECUTION_PLAN、CE04_MEMBER，从未完成部分连续执行；边界和Git已授权，保护原商城/OA和私密数据。
