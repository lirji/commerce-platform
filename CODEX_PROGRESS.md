# Codex Progress

## 任务目标

连续完成已批准商城中央员工权限扩展。OA为员工/部门权威；会员/营销按真实TENANT_ALL，库存/交易按真实门店；高风险独立权限，沿现有业务流程，不新增OA逐笔审批。任务分支、正常合并推送main已授权，不含生产部署或数据清理。原目录串行，不创建子Agent。

## 已完成

- CE02/CE03及CE04基础会员P、成长G、标签T、行为B、周期C0—2已交付，详细证据见auth docs/implementation/oa-auth/commerce-readiness/CE04_MEMBER.md。
- C2 auth02cfdd7/commerce3c8a689；432项427PASS/5skip，真实27a84a8559ec 341PASS。commerce CI36681436387原版重跑SUCCESS，首轮既有并发屏障超时证据保留，未修改测试预算。
- PTS0 authce29f5b：五积分有限执行能力，252单元/真实PG+graph9及SDK/Boot4/package PASS，CI36681665256 SUCCESS。
- PTS1 auth67d4978/commercef1b99cb已正常合并推送main；五能力、MEMBER_POINTS族/V57、实际Owner/事务审计；完整438项433PASS/5skip，真实0c2048fafb9f（子网97）329PASS。两仓CI36682764819/36682762638 SUCCESS。
- PTS2实现完成：积分SSO页、三个独立hint、247入口契约；全量points-ui-verify.log 439项434PASS/5skip，前端build/Prettier、36工具及两仓hygiene通过。

## 已修改文件

- auth feat/commerce-points-ui-contract：CONTRACTS_COMMERCE_POINTS、bindings/HTTP_INVENTORY/测试247、P6浏览器接线和governance-ce04-points.mjs。
- commerce feat/central-points-ui：CentralPoints.tsx及SSO/session/main、PointsActionsController、精确路由/Errors/staticGET、CentralPointsMySqlTest/AuthorizationCoverageTest。
- 两仓私密points-ui-source-sha256.json已记录当前代码；文档更新不影响代码摘要。

## 未完成

- PTS2本地DONE：真实b5f6eeb0aebf/子网98共397PASS、积分11浏览器检查，实际截图和SQL审计/源码摘要一致；session76550已成功结束，自有进程finally停止，私密证据保留。正在同步并正常Git交付。
- 后续CE04-O积分商品有限协议/Owner/UI，CE05—08尚未完成。原63节点DAG的生产2HOLD不变，不阻塞已授权本地扩展。

## 当前问题

- 专用MySQL commerce-inventory-mysql-7841e58190:43308 / commerce_test_20260923，source commerce/.local/runtime.env与.local/central-inventory/owned.env；不能输出凭据。V49—V57已应用不可修改。
- 子网81—98保留数据；自有演练进程finally停止。原8602/commerce_local不切换。私密.local证据不提交不删除。
- Java formatter/静态分析未配置限制保持。5秒只代表本地准入期限，生产容量目标待定。
- OA脏文件、auth其他工作树与commerce旧基线保留；本任务未建工作树。不与P6并行全量MySQL测试。

## 下一步建议

1. PTS2已通过必需验证，核对当前状态后正常Git交付。
2. 更新PTS2结果/状态与CODEX_PROGRESS，核对摘要、必要检查后按当前分支正常合并推送main。
3. 连续开始CE04-O0三项point_offer有限能力（read/define/status.update），细化实际Owner与客户兑换兼容，然后O1/O2及CE05—08。

## 恢复 Prompt

读取CODEX_PROGRESS与auth CE04_MEMBER/EXECUTION_PLAN，从PTS2 session76550真实浏览器验证继续。不要重做PTS0/1；正常Git交付已授权，不逐片询问。保护原环境、其他任务和私密证据，高风险独立权限沿现有业务流程。
