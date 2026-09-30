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

- auth feat/commerce-tag-ui-contract：TAG正式T2契约、bindings/HTTP_INVENTORY及计数234、P6标签浏览器阶段、governance-ce04-tags.mjs。
- commerce feat/central-tags-ui：CentralTags页面、SSO/session/main、TagActionsController、中央精确过滤/错误/静态GET安全、CentralTagMySqlTest第6项和壳门禁测试。

## 未完成

- T2本地DONE：真实fdda68438ef6共221PASS，标签10/成长11/会员12/目录11/库存9/CATALOG9浏览器检查，1440/390/字典/关联/重试/409/撤销/503截图已实际查看，API+UI恰7条身份审计。自有PG/IdP/JVM/Vite已finally停止，子网87和数据保留。当前仅待显式路径Git交付/CI，继续B。
- 下一CE04-B行为：已只读盘点BehaviorService/Controller；profile及detail/events真实Member Owner；rebuild现从orders.adminList扫描最多50订单并投影，需正式细化独立权限及内部门禁，禁止伪装ADMIN。
- 周期/积分、CE05—08未完成。原DAG63节点61DONE/2productionBLOCKED不变；真实OA映射、Owner签字/授权截止、生产容量SLO/RTO/RPO目标待定仅阻塞对应真实动作。

## 当前问题

- 发券既有500ms/20条预算测试并行重负载时敏感，保留历史，未放宽断言；订单零尝试仍推进游标的真实公平性bug已独立45b74ee修复。
- 专用MySQL commerce-inventory-mysql-7841e58190:43308，commerce_test_20260923；source commerce/.local/runtime.env再source .local/central-inventory/owned.env，不输出凭据。V49—V54不可改历史。原8602不切换。
- Docker默认地址池耗尽，已保留子网81—87及数据，自有演练进程应finally停止。私密.local证据/配置不提交不删除。
- OA既有脏文件、auth其他工作树与Docker提交原样保留。本任务未建新worktree；不清理旧基线。
- 无Java formatter/静态分析的hygiene限制保持；5秒仅本地准入期限，不承诺跨库瞬时撤权。

## 下一步建议

1. T2验收通过，按明确路径提交普通合并推送两仓并查CI。
2. 正式化CE04-B行为契约，串行实施协议、Owner/业务路由、页面和真实联调；继续后续已批准能力，不每片暂停。

## 恢复 Prompt

读取CODEX_PROGRESS及auth commerce-readiness/EXECUTION_PLAN、CE04_MEMBER和对应契约，从T2验收/交付继续到CE04-B及后续已批准范围。边界和Git均已授权，不重复确认，不把一片完成当总体完成；保护其他任务和私密数据。
