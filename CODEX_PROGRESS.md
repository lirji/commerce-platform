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

- auth feat/commerce-behavior-ui-contract（基线main6a0e397）：P6行为五浏览器阶段、governance-ce04-behavior.mjs（新）、bindings/HTTP_INVENTORY/test计数237。
- commerce feat/central-behavior-ui（基线mainf6a2d2f）：CentralBehavior.tsx（新）、SSO/session/main、BehaviorActionsController（新）、中央精确路由/Errors/staticGET、CentralBehaviorMySqlTest第6项和壳安全检查。

## 未完成

- B2本地DONE：425项420PASS/5skip，真实1d02cf3bcffd267PASS含行为11条浏览器及所有旧页回归；当前截图已查看，36工具/237入口/前端build/最终hygiene PASS（缺formatter/static限制），两仓源码sha已复核。首轮d1cbe3315c16主动中止修审计过滤的历史保留。
- B2正在按授权精确路径提交、普通合并推送main；B1 CI36676623565/36676628487均SUCCESS。B0 f7fa425 CI36675295449 SUCCESS。
- 下一CE04-C周期/权益：只读盘点和私有contract-final-draft已完成，尚无实现；需保护既有系统事件发放，不把内部依赖套员工read门禁。再积分与CE05—08。
- 原DAG63节点61DONE/2生产HOLD不变，真实OA映射/Owner签字/截止/生产SLO/RTO/RPO待定只阻塞对应真实动作。

## 当前问题

- 发券既有500ms/20条预算测试并行重负载时敏感，保留历史，未放宽断言；订单零尝试仍推进游标的真实公平性bug已独立45b74ee修复。
- 专用MySQL commerce-inventory-mysql-7841e58190:43308，commerce_test_20260923；source commerce/.local/runtime.env再source .local/central-inventory/owned.env，不输出凭据。V49—V55不可改历史。原8602不切换。
- Docker默认地址池耗尽，已保留子网81—92及数据，自有演练进程应finally停止。私密.local证据/配置不提交不删除。
- OA既有脏文件、auth其他工作树与Docker提交原样保留。本任务未建新worktree；不清理旧基线。
- 无Java formatter/静态分析的hygiene限制保持；5秒仅本地准入期限，不承诺跨库瞬时撤权。

## 下一步建议

1. 完成B2 Git交付、查CI；本地验收已完整通过。
2. 立即继续C0中央七能力协议、C1实际Owner/两个独立族与V56，再C2页面；不每片暂停。

## 恢复 Prompt

读取CODEX_PROGRESS及auth commerce-readiness/EXECUTION_PLAN、CE04_MEMBER和CONTRACTS_COMMERCE_BEHAVIOR，从B2页面验证继续。边界和Git已授权，不重复确认，不把一片完成当总体完成；保护其他任务、原商城和私密数据，无子Agent。
