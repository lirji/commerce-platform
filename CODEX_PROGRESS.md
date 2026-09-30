# Codex Progress

## 任务目标

连续完成已批准商城中央员工权限扩展。OA为员工/部门权威；会员/营销真实TENANT_ALL，库存/交易真实门店；高风险独立权限、沿用现有业务审批，不新增OA逐笔审批。授权任务分支、正常合并推送main，不含生产部署/清理数据。按原目录串行推进，不生成新工作树，不反复询问继续。

## 已完成

- CE02、CE03库存I/U及目录D0/D1/D2已交付；完整历史见auth commerce-readiness各CE03文档。
- CE04-P0协议auth dd07223已推送，CI36668768692 SUCCESS。
- CE04-P1 auth2cb8c11/commerce1bc81f2已推送，CI36669783915/36669785165 SUCCESS。406项401PASS/5skip，真实隔离114PASS（rehearsal-451c622849b4）。V52已在测试/演练应用不可改历史。
- CE04-P2 auth21380a4/commerce359ac02已推送。407项402PASS/5skip，最终rehearsal-aa0b96471601共137PASS（会员12条浏览器、目录11/库存9/CATALOG9），当前1440/390/表单/历史/未知/冲突/故障截图已实际查看。36项Python/227入口/hygiene无阻断。auth CI36670574749被G0推送取消，commerce CI36670578849 SUCCESS。
- CE04-G0 auth51f637c7fc9dbff730da369adbd37c73b8a4db0e已推送，CI36670815592 SUCCESS（含P2基线）。成长5能力：member实例3、policy集合2，60秒HUMAN/完整TENANT_ALL，policy resource-check拒绝，独立Owner登记。251既有单测+新增Owner1、5真实PG+graph IT（自有PGf7cc1b4cd6cd已停）、SDK install/Boot4/package/hygiene PASS。正式CONTRACTS_COMMERCE_GROWTH.md已细化G0/G1/G2。

## 已修改文件

- auth feat/commerce-growth-owner-contract：已改deploy/governance-p6-rehearsal.py新增--growth真实联调，正式成长契约追加G1锁/兼容细节。
- commerce feat/central-member-growth：EmployeeAccess成长5能力/MEMBER_GROWTH族；EmployeeAuthority全租户与会员许可；MemberGrowthService政策/钱包/账本/调整/重算；精确过滤/Errors；V53（已在专用MySQL成功应用，不可改历史）；CentralGrowthMySqlTest4项；SDK来源固定51f637c且原安装脚本PASS；CODEX_PROGRESS更新。

## 未完成

- G1完整串行growth-verify-serial.log已411项406PASS/5skip，真实rehearsal-fb6d4a99a58b已151PASS，无浏览器；36项Python、227入口及两仓hygiene无阻断。G1本地DONE，Git交付中。
- 下一G2成长UI：私有草案.local/governance/commerce-contracts/growth-ui-contract-draft.md待正式化，沿用SSO/AntDesign与三个独立写提示，真实浏览器/1440/390截图验收。
- 成长之外标签/行为/周期/积分、CE05—08仍未完成。真实OA映射/Owner签字/永久授权截止/生产SLO RTO/RPO待定，仅阻塞对应真实动作。

## 当前问题

- Docker默认地址池耗尽，不删历史资源。P6支持--identity-subnet明确RFC1918 /24，已用10.254.81/82/83.0/24保留，84已停止进程并保留网络，下一可核对后选85；Docker检查重叠。PG-only协议IT不需新网络。
- P1初次测试故障注入触发MANDATORY代理，改AopTestUtils设置spy；库存对现会员入口期望401改403。P2 e56215a12fb5到108PASS后Select测试role定位超时，菜单正常，改可见选项定位，最终aa0b96471601完整通过。失败日志/截图均保留。
- 专用MySQL commerce-inventory-mysql-7841e58190:43308继续运行，凭据只source不输出；测试库commerce_test_20260923。V49—V52已执行不改；V53已应用也不可改历史。原8602/commerce_local不切换、不重启。
- OA6385a86的既有脏文件不碰。auth他任务782ae3d仅gitignore且已在origin/main，保留其~/.local/share/git-worktrees/auth-platform/gitignore-hygiene工作树；本任务没有新建worktree。
- .local私密证据/临时源草案、停止的PG/IdP/网络和隔离库保留不提交不删除。旧二进制不认识新能力族，回退需认识族的版本+STOPPED；5秒仅本地准入窗口，不承诺跨库瞬时撤权。

## 下一步建议

1. G1按明确路径提交/普通合并推送两仓并查CI；不修改已验证代码。
2. G2正式化现有技术草案后实施页面与提示、真实浏览器联调。
3. 成长完成后继续会员其他能力及CE05—08，不把一片当全部完成。

## 恢复 Prompt

读取CODEX_PROGRESS与auth docs/implementation/oa-auth/commerce-readiness/EXECUTION_PLAN、CE04_MEMBER、CONTRACTS_COMMERCE_GROWTH，从未完成的G1回归/真实联调继续；边界与Git已授权，不重复确认，保护原商城/OA和私密数据。
