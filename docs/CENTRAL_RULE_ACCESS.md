# 营销规则中央员工权限

沿 auth `CONTRACTS_COMMERCE_RULES.md`，SDK固定 `5049380da6f9c06413c7d63b5df6c226aeaf53f1`。V62追加RULE独立接管族和marketing_rule实际审计类型，不自动切换租户。rule.read/create/publish均TENANT_ALL，三个权限独立；字段目录由MarketingAssets.ruleFields统一校验read，旧ADMIN不能绕过已接管族。

创建沿原RuleNode字段/类型/深度/数量/重复条件校验，不接受任意客户端事实。目录保持每个ruleId最新版本、稳定游标；字段和目录返回前复核授权范围。两个写操作先锁权威路由及核验期限，再读取旧幂等回执；中央仅向原意图增加稳定身份，旧模式摘要不变。

发布通过真实tenant+ruleId+assetVersion读取事实，事务内锁路由、再锁实际版本、复核事实与期限。资产version是不可变内容版本，不是状态修订；原DRAFT→PUBLISHED及“已发布版本的新命令仍成功”语义保持。实际ruleId身份审计、状态和命令回执同事务，具体资产version可由同命令响应关联；审计失败整笔回滚。

publishedRule内部接口仍只接受存在且已发布的固定版本，员工撤权或RULE停止不撤销既有交易引用。字段、规则目录和发布权限不授予活动审批、人群或客户身份。V49—V62已执行迁移不得修改；回退需使用识别RULE族的兼容版本。

本地完整468项463PASS/5既有skip，CentralRuleMySqlTest7方法全PASS，含64字编码HTTP路径、独立读写、代际和原键、审计回滚、真实版本、范围/撤权/STOPPED/503与可信内部引用兼容。42工具/256入口、两仓hygiene及auth2/commerce12源码摘要一致；真实跨进程联调518PASS，结果由auth CE05_MARKETING记录。Java formatter/静态分析未配置限制保持；规则页面另归R2。


## CE05-R1验收

CE05-R1本地DONE：RULE独立接管族、规则read/create/publish、真实不可变版本锁、原回执前权限复核与同事务审计，V62已应用不可改。完整468项463PASS/5既有skip，规则7项全PASS；最终真实7b1ff652c183（10.254.110.0/24）518PASS，无浏览器。42工具/256入口/122能力/34角色、两仓hygiene及auth2/commerce12源码摘要一致。下一R2规则员工页；其余CE05—08与原生产2HOLD未完成。

|验收|结果|私密证据|
|---|---|---|
|真实MySQL与全仓|468项463PASS/5skip，规则7方法覆盖独立两写/读取、实际版本/64字编码HTTP、原键/代际、审计回滚、撤权/STOPPED/503、原规则树限制及可信内部引用|commerce-contracts/rules-owner-verify.log|
|真实授权联调|518PASS；3次规则创建与2个独立发布命令恰5身份审计；同键不重复；旧版本1=PUBLISHED、最新版本2=DRAFT，目录不回退旧已发布版本；字段目录14项且独立read，撤权/停机失败关闭|p6/rehearsal-7b1ff652c183/result.json及检查点|
|超过50条授权的投影|三次实际POLICY投影分别APPLIED→READY共2批；只有最终READY才继续业务验收|同目录projection-batches-*.json及关联CLI日志|
|质量与版本|42工具/256入口、两仓hygiene、auth2/commerce12源码摘要一致；Java formatter/静态分析限制保留|commerce-contracts/rules-owner-{tools-batches,contract-final,auth-hygiene-batches,commerce-hygiene,source-sha256}*|

首轮41130bc13832/子网109在475PASS后停于APPLIED，源于旧演练脚本假定单次CLI完成全量。生产ReliableProjection每批最多50条，本轮累计授权跨越该边界。只修演练调度：最多8批、总45秒，子进程使用剩余预算，仅APPLIED/RECOVERED推进，READY才通过；BUSY/RETRY_WAIT/BLOCKED、未知或不匹配状态立即拒绝。新增5项工具测试证明边界、状态及超时失败关闭。未改产品投影器、商城业务、Java验证版本或生产预算；失败日志保留。

自有进程finally停止，原8602/OA未切换，无新worktree；两轮私密库/日志保留。R0 auth5049380精确CI36699041572 SUCCESS，包含E2 auth基线；E2 commerce CI36698659860 SUCCESS。R1 implementation-validation COMPLETED/PASS，update-progress-docs DONE（本地）；Git/CI另记。
