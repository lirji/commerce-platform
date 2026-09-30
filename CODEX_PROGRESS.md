# Codex Progress

## 任务目标

连续完成已批准商城中央员工权限扩展。OA为员工/部门权威；会员/营销按真实TENANT_ALL，库存/交易按真实门店。高风险操作独立权限，沿现有业务流程，不新增OA逐笔审批。任务分支及正常合并推送main已授权，不含生产部署/数据清理。原目录串行，不创建子Agent。

## 已完成

- CE02/03与CE04基础会员P、成长G、标签T、行为B、周期C、积分PTS0/1/2已交付。详细证据见auth docs/implementation/oa-auth/commerce-readiness/CE04_MEMBER.md。
- PTS2 auth8fe0e1f/commerce175bf78：439项434PASS/5skip；真实b5f6eeb0aebf/子网98共397PASS，积分11条浏览器及实际1440/390截图、账本/审计一致。commerce CI36684041293 SUCCESS；auth被O0取消，O0成功包含该基线。
- O0 authc0d0745已正常合并推送；252单元/真实PG c7f5dd5f5958+授权图10/SDK/Boot4/install/hygiene PASS，CI36684307399 SUCCESS。三有限point_offer能力，不扩大客户身份。
- O1实现完成：POINT_OFFER族、三能力、实际商品Owner停启、两写事务审计、共享客户目录非MEMBER无旁路；会员兑换保持原事务。SDK固定c0d0745。修复后offers-verify-fixed.log共445项440PASS/5skip，新CentralPointOfferMySqlTest6项全PASS。两仓hygiene、37工具/247入口通过。

## 已修改文件

- auth feat/commerce-point-offer-owner-rehearsal：P6 --offers与启动前嵌套制品校验、新test_governance_p6_artifacts.py、POINT_OFFERS契约/CE04_MEMBER文档。最终源码摘要.local/governance/commerce-contracts/offers-source-final-sha256.json两文件。
- commerce feat/central-point-offer-operations：PointOfferService/Controller、EmployeeAccess/Authority、CentralEmployeeConfiguration/Service/Errors、SDK ref、V58/V59、CentralPointOfferMySqlTest、CENTRAL_POINT_OFFER_ACCESS与CODEX_PROGRESS。源码摘要.local/central-inventory/offers-source-sha256.json十一文件。

## 未完成

- O1本地DONE：最终session73195/334c444f50a5子网101共376PASS，SQL余额/额度/回执/审计和源码摘要一致，自有进程finally停止。正在正常Git交付。
- O2员工积分兑换商品页及两个独立hint。私密细化草案auth .local/governance/commerce-contracts/point-offer-ui-contract-draft.md，尚未实现。
- CE05—08未完成；原63节点DAG生产2HOLD保持，不阻塞已授权本地扩展。

## 当前问题

- 第二轮79acb92b5686/子网100在337PASS后因积分临时夹具readiness证据文件重名停止。已只修改offer_grant的phase为offer-fixture；当前最终session73195/子网101重跑，业务代码不变。

- O1首轮offers-verify.log新6项因审计约束未允许point_offer失败，V58已应用不改；追加V59修复，完整重跑已PASS。V49—V59已应用不可改。
- 首轮真实2f41e53f63a3/子网99在315PASS后point_offer.define签发403：实际server JAR嵌套governance为旧版。已maven.jar.forceCreation=true package，核验admin/server内protocol/core/governance与当前模块摘要一致，新server含有限能力。P6新增制品校验和离线旧嵌套依赖回归；不改权限或等待预算。首轮失败日志/数据保留、自有进程finally停止。
- 专用MySQL commerce-inventory-mysql-7841e58190:43308 / commerce_test_20260923；source commerce/.local/runtime.env及.local/central-inventory/owned.env，不能输出凭据。不与P6并行全量MySQL测试。
- 子网81—101数据保留；原8602/commerce_local不切换。私密.local不提交不删除。Java formatter/静态分析未配置限制保持，5秒仅本地准入期限。
- OA已有脏文件、auth其他工作树与commerce旧基线保持；本任务未建工作树。

## 下一步建议

1. O1最终已PASS，核对显式暂存路径并正常Git交付。
2. O1通过后同步文档，显式路径提交，正常合并推送main，查CI。
3. 连续创建O2任务分支，按已批准契约实现SSO兑换商品目录/定义/状态三Tabs、两个独立hint和真实浏览器测试，再CE05—08。

## 恢复 Prompt

读取CODEX_PROGRESS与auth CE04_MEMBER/EXECUTION_PLAN，从O1已376PASS后的Git交付及O2继续。不要重做PTS/O0或已通过全量测试，不逐片询问是否继续。Git交付已授权，保护原环境和私密证据；V58/V59不可改，失败补追加迁移。高风险拆权限沿既有流程，不新增OA审批。
