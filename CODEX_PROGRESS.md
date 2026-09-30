# Codex Progress

## 任务目标

连续完成已批准商城中央员工权限扩展。OA为员工/部门权威；会员/营销真实TENANT_ALL，库存/交易真实门店；高风险独立权限并沿用现有流程，不新增OA逐笔审批。任务分支、普通合并推送main已授权；不含生产部署/清理数据。原目录串行，无子Agent。

## 已完成

- CE02、CE03与CE04-P基础会员、G成长已交付，历史/失败证据见auth docs/implementation/oa-auth/commerce-readiness/CE04_MEMBER.md。
- G2 auth8351043/commerce646ebd5已推送；commerce CI36673407016 SUCCESS含独立轮转修复45b74ee；auth G2 CI取消，后续T0 CI36673611219 SUCCESS含基线。真实成长浏览器324fea9731d5共183PASS。
- T0 auth6bfaae7已交付，252单元/真实PG+graph6项/SDK Boot4 package PASS，CI36673611219 SUCCESS。
- T1 auth10239a5/commercea85e62f已交付；commerce CI36674311269 SUCCESS，auth CI36674310063 SUCCESS。完整417项412PASS/5skip加最终标签5项PASS；真实c1893723fb4a共182PASS。V54已应用不可修改。
- T2页面与两个独立提示已实现；完整tag-ui-verify.log共419项414PASS/5既有skip，36项Python/234入口契约通过。Hygiene发现标签操作魔法字符串，已改为封闭TagOperation常量，无业务语义变化，最终build/package/hygiene已PASS。

## 已修改文件

- auth feat/commerce-behavior-owner-contract：deploy/governance-p6-rehearsal.py（--behavior）、CONTRACTS_COMMERCE_BEHAVIOR.md（B1/B2技术细化）、CE04_MEMBER.md（验证与失败证据）。
- commerce feat/central-member-behavior：MemberBehaviorService、MemberBehaviorController、MemberBehaviorRebuildService（新应用用例）、EmployeeAccess/EmployeeAuthority、CentralEmployeeConfiguration/CentralStoreErrors、OrderApi/OrderService/OrderMapper及XML、V55、CentralBehaviorMySqlTest、scripts/auth-sdk-source.ref、CENTRAL_BEHAVIOR_ACCESS/CENTRAL_MEMBER_ACCESS、CODEX_PROGRESS。
- B1测试后仅改Controller中文注释，产品语义未再改变。T2及B0已交付，不混入新提交。

## 未完成

- T2本地DONE：真实fdda68438ef6共221PASS，标签10/成长11/会员12/目录11/库存9/CATALOG9浏览器检查，1440/390/字典/关联/重试/409/撤销/503截图已实际查看，API+UI恰7条身份审计。自有PG/IdP/JVM/Vite已finally停止，子网87和数据保留。已交付authfd9ef83/commerce4aafed1，auth CI36675135942被B0取消，commerce36675136986 SUCCESS，继续B。
- CE04-B0已推送authf7fa425，CI36675295449 SUCCESS（T2 auth36675135942因后续push取消，非失败或通过）。252单元、真实PG ebabed7fcae1+graph7方法、SDK/Boot4/package/hygiene PASS，自有PG已停。
- B1本地DONE：commerce feat/central-member-behavior：三行为能力/MEMBER_BEHAVIOR族；真实Member Owner/前后读复核/写guard/同事务审计、客户本人规则保留；专用应用重建服务与订单Owner仅id+createdAt有限内部端口；V55扩族/实际持久批次审计type。SDK固定f7fa425并安装，compile PASS。完整behavior-verify.log已424项419PASS/5skip（新行为5项及原行为6项PASS），两仓hygiene无阻断。V55已随测试启动应用，禁止改历史。
- auth feat/commerce-behavior-owner-contract：P6 --behavior已加显式3有限角色、独立资料/重建/读、真实客户事件与隔离历史订单/成长来源种子、审计/撤权/503。语法/36Python/234入口PASS；实际第一轮c1c16e0bba56在169PASS后脚本错误调用/grants收到400；已按现有/scoped-grants真实契约修复字段/202/execution_ready，不改业务/断言。第二轮b849379ea0ae已207PASS含行为全链路，但行为batch变量覆盖原迁移batch致末尾MigrationImportCli失败；仅脚本改behavior_batch，未改产品/断言。第三轮31b246afdc39共221PASS（无浏览器），behavior_checked=true，runtime_switched/production_ready=false，自有PG/IdP/JVM已停，子网88—90/数据保留。behavior-source-sha256两仓源码摘要复核一致。B2页面正式技术细化已追加契约，尚未实施。
- 周期/积分、CE05—08未完成。原DAG63节点61DONE/2productionBLOCKED不变；真实OA映射、Owner签字/授权截止、生产容量SLO/RTO/RPO目标待定仅阻塞对应真实动作。

## 当前问题

- 发券既有500ms/20条预算测试并行重负载时敏感，保留历史，未放宽断言；订单零尝试仍推进游标的真实公平性bug已独立45b74ee修复。
- 专用MySQL commerce-inventory-mysql-7841e58190:43308，commerce_test_20260923；source commerce/.local/runtime.env再source .local/central-inventory/owned.env，不输出凭据。V49—V54不可改历史。原8602不切换。
- Docker默认地址池耗尽，已保留子网81—87及数据，自有演练进程应finally停止。私密.local证据/配置不提交不删除。
- OA既有脏文件、auth其他工作树与Docker提交原样保留。本任务未建新worktree；不清理旧基线。
- 无Java formatter/静态分析的hygiene限制保持；5秒仅本地准入期限，不承诺跨库瞬时撤权。

## 下一步建议

1. B1所有本地验证已通过，精确路径提交普通合并推送两仓并查CI；立即B2，不停在后端。
2. B1证据/文档/进度/Git交付后继续B2页面与真实浏览器；其余已批准模块不断片。

## 恢复 Prompt

读取CODEX_PROGRESS及auth commerce-readiness/EXECUTION_PLAN、CE04_MEMBER和对应契约，从T2验收/交付继续到CE04-B及后续已批准范围。边界和Git均已授权，不重复确认，不把一片完成当总体完成；保护其他任务和私密数据。
