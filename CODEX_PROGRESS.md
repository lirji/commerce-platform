# Codex Progress

## 任务目标

连续完成已批准商城中央员工权限扩展。OA为员工/部门权威；会员/营销真实TENANT_ALL，库存/交易真实门店；高风险独立权限、沿用现有审批。授权任务分支与普通合并推送main，不含生产部署或删除数据。

## 已完成

- CE02契约/协议已交付；库存I/U已交付，两仓CI SUCCESS。
- D0 auth2557de1已推送，CI36666736638 SUCCESS；250单测/3真实PG+graph IT/Boot4兼容PASS。
- D1后端本地DONE：401项396PASS/5skip；rehearsal-16693ea0d846真实中央+IdP+商城83PASS；hygiene无阻断。V51成功应用，禁止改历史。

## 已修改文件

- commerce EmployeeAccess/Authority、CentralEmployee适配、Merchant/Store Service与Mapper、V51、CentralDirectoryMySqlTest及CENTRAL_DIRECTORY_ACCESS.md。
- auth目录契约、P6 --directory隔离验证工具、CE03_DIRECTORY和进度记录。

## 未完成

- D1 Git交付进行中（auth feat/commerce-directory-owner-contract、commerce feat/central-commerce-directory）。
- 下一READY D2目录SSO页面；CE04—08未实现，不把D1当全任务完成。
- 真实OA映射/Owner签字/永久授权截止/生产SLO及RTO/RPO待定，仅阻塞对应真实动作。

## 当前问题

- 原8602/OA脏改动不触碰；没有新worktree。私密.local与历史失败证据保留不提交。
- 专用MySQL commerce-inventory-mysql-7841e58190端口43308运行供后续；commerce source .local/runtime.env再source .local/central-inventory/owned.env测试，不输出凭据。
- D1自有PG/IdP/JVM已由finally停止，数据保留。共享测试库此前V49失败已受控repair并原样应用49/50，空残留改名保留；V51只在专用库应用。
- 旧CE03-U二进制不检查DIRECTORY，回退必须使用认识该族版本+STOPPED。5秒为事务准入，并非全局瞬时撤权。

## 下一步建议

1. 按已验证D1范围显式stage、提交、正常合并推送main并核对CI。
2. 补D2动作提示与页面技术契约后实现真实SSO页面和浏览器验证，继续CE04—08。

## 恢复 Prompt

读取CODEX_PROGRESS及auth commerce-readiness/EXECUTION_PLAN，从未完成部分连续推进。已获业务边界和Git授权，不重复确认，保护原商城/OA改动及私密数据。
