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


## 规则员工入口（CE05-R2本地DONE）

固定 `/operations/rules?tenant_id=<组织UUID>` 通过现有PKCE壳登录。规则目录与可信字段只使用rule.read；创建和发布分别调用 `/v1/operations/rules/create-access`、`publish-access`，不隐含目录权限。按已知编号和不可变内容版本发布，无需先读目录；提交时仍核验实际规则Owner。

创建复用受限RuleEditor及服务端可信字段/资源上限，目录用RuleSummary展示最新版本。输入正安全整数版本、64字标识与128字名称；发布请求无请求体，编码真实id和version。409保留可纠正输入；响应丢失则冻结原键/体/目标和递归编辑器，切Tab/取消退出原样重试。两个动作的未保存状态各自记录，一个成功不能清除另一个未知结果；401卸载，403独立拒绝，503关闭写入并可重核验。

本轮不新增迁移，不改变规则业务状态；R2验收已通过。旧专用MySQL已不存在，首次完整测试连接拒绝日志保留；已建立独立mysql8.4实例commerce-rules-mysql-698708fb5f/43308，凭据仅在私密owned-rules.env，旧配置和共享环境未覆盖。


## CE05-R2验收

CE05-R2本地DONE：固定SSO规则目录/创建/发布三Tab、两个独立hint与两个原意图分别恢复；完整469项464PASS/5既有skip、规则8专项全PASS，最终真实200dbeb9873c（子网10.254.114.0/24）609PASS，规则12条浏览器与全部既有员工页回归通过。42工具/259入口/122能力/34角色、build/Prettier/两仓hygiene及auth4/commerce10源码摘要一致；实际8条规则身份审计、UI创建/发布各1次，同键不重复，旧1=PUBLISHED/最新2=DRAFT。1440/390表单/目录、409/未知/成功/503已查看；稳定布局后的正文与PNG均390，表格内部横滚。无新迁移，V49—V62不可改；下一CE05-A人群快照，其余CE05—08和原生产2HOLD未完成。

|验收|结果|私密证据|
|---|---|---|
|事务与两个独立hint|469项464PASS/5skip，规则8专项全PASS；hint不写审计，无read两写、独立撤权、旧ADMIN/401/503|commerce-contracts/rules-ui-verify-restored-db.log|
|真实页面与授权链路|609PASS；规则12条包含PKCE、409保留、可信树重复纠错、创建未知期间独立发布成功仍保留创建、两笔原键/原体或无体/编码路径恢复、最新目录、独立撤权、跨租户/401/实际503|p6/rehearsal-200dbeb9873c/result.json与rules-*-result.json|
|数据库结果|8规则审计；UI ce05-rule:c创建/发布各1次，ce05-rule:a发布3个独立命令；同键不重复，a旧1=PUBLISHED/新2=DRAFT，c1=PUBLISHED|同目录检查点及精确SQL断言|
|视觉与截图时机|已查看1440/390表单与目录、409/未知/成功/503；稳定布局后3个窄屏PNG和文档/正文均390，表格内部横滚；取消退出功能两次通过|rules-390-*-width.json、rules-*.png|
|质量与源码|42工具/259入口、build/Prettier/package/两仓hygiene及auth4/commerce10摘要一致；原Java formatter/静态分析限制和5既有skip保留|commerce-contracts/rules-ui-*|

首次Java测试因旧专用MySQL已不存在而连接拒绝，已恢复独立commerce-rules-mysql-698708fb5f/43308，私密owned-rules.env保留，旧owned.env不覆盖。首轮浏览器103408e2a0ce/子网111在545PASS后隐藏Ant Design option定位停止，已改可见下拉精确文本。第二轮a1c60c4dcb31/子网112功能609PASS，但取图尺寸643；第三轮2c4a7f48441a/子网113在554PASS后更严格PNG断言停止。文档/正文均390的证据与本地Playwright取图实现确认响应式Descriptions布局时机，早取诊断632、等字体及两帧后两次390。该诊断重放真实库摘要只用于布局，不作授权证据。最终脚本等字体/两帧并poll完整scroll/offset/client宽度，仍严格要求正文和实际PNG390，未裁图/放宽阈值；容器上限只作用本页。

本轮自有进程finally停止，原8602/OA未切换，无新worktree；四轮失败/成功库与私密证据、构建依赖及其他worktree保留。R1 auth8a54b0c/commerce74a5cf4，CI36701575411/36701590580均SUCCESS。R2 implementation-validation COMPLETED/PASS、update-progress-docs DONE（本地）；Git/CI另记。
