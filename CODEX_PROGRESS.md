# Codex Progress

## 任务目标

连续完成已批准商城中央员工权限扩展。OA为员工/部门权威；会员/营销真实TENANT_ALL，库存/交易真实门店；高风险独立权限、沿用现有审批。授权任务分支、普通合并推送main，不含生产部署或删除数据。

## 已完成

- CE02契约/协议，库存I/U已交付，两仓CI SUCCESS。
- D0 auth2557de1已推送，CI36666736638 SUCCESS。
- D1 auth378313c与commerce37e2e06已推送；commerce固定旧SDK导致CI36667458010编译失败，独立49274a8固定auth2557de1后CI36667650544 SUCCESS。auth CI36667456414 SUCCESS。
- D2本地DONE：402 Java项397PASS/5skip，99项真实隔离检查（目录11条浏览器、库存/CATALOG各9条），1440/390/创建/未知/故障截图已查看。证据auth CE03_DIRECTORY.md与rehearsal-ae9ef79aba17。223入口、Python9契约+22工具测试、前端build/Prettier/package与hygiene无阻断。

## 已修改文件

- commerce CentralDirectory页面与SSO路由、DirectoryActionsController/精确过滤链/Errors/两项测试及文档。
- auth目录契约/223入口、P6演练与新目录浏览器工具、CE03_DIRECTORY和进度记录。

## 未完成

- D2 Git交付进行中：auth feat/commerce-directory-ui-contract；commerce feat/central-directory-ui。需明确stage后提交、普通合并推送并核对CI。
- 下一READY CE04会员核心/成长/标签/行为/周期/积分分片；CE05—08未做，不把目录接入当全任务完成。
- 真实OA映射/Owner签字/永久授权截止/生产SLO与RTO/RPO待定，仅阻塞对应真实动作。

## 当前问题

- D2失败历史：ce6a9ff06d90到52PASS后私密文件exclusive覆盖被拒，改独立directory-ui.json；一次准备主动中止避免重复ready文件，按相位保存；fbd07aa28788重复表单ID标签错误，修复Form name后ae9ef79aba17最终PASS。记录保留，未放宽业务断言。
- 原8602/OA脏改动不触碰；没有新worktree。私密.local及停止容器/网络/数据保留不提交、不删除。所有D2PG/IdP/JVM/Vite已由finally停止。
- 专用MySQL commerce-inventory-mysql-7841e58190端口43308仍运行供后续；commerce先source .local/runtime.env再source .local/central-inventory/owned.env，不输出凭据。
- V49/V50/V51已成功执行不得改历史。旧CE03-U二进制不检查DIRECTORY，回退使用认识该族版本+STOPPED；5秒是准入而非跨库瞬时撤权。

## 下一步建议

1. 完成D2 Git交付/远程CI。
2. CE04从会员核心4能力独立族细化（MemberService stats被Dashboard调用，不能让旧聚合绕过）；成长/积分/周期/行为保留客户本人读取，禁止旧ADMIN绕过；PointsSpend内部订单/退款不混员工授权。
3. 新增会员执行协议需要真实目标Facts与集合许可，不能伪造会员/政策资源；尚未设计/实现CE04。

## 恢复 Prompt

读取CODEX_PROGRESS及auth commerce-readiness/EXECUTION_PLAN，从未完成部分连续推进。业务边界和Git已授权，不重复确认，保护原商城/OA改动及私密数据。
