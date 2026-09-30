# Codex Progress

## 任务目标

连续完成已批准商城中央员工权限扩展。OA为员工/部门权威；会员/营销真实TENANT_ALL，库存/交易真实门店；高风险独立权限并沿用现业务流程，不新增OA逐笔审批。用户授权任务分支、正常合并推送main；不含生产部署/清理数据。原目录串行推进，不用子Agent，不要求反复继续。

## 已完成

- CE02、CE03库存I/U及目录D0/D1/D2已交付；完整历史见auth docs/implementation/oa-auth/commerce-readiness。
- CE04-P0/P1/P2已交付。P1 auth2cb8c11/commerce1bc81f2两仓CI SUCCESS；P2 auth21380a4/commerce359ac02，commerce CI36670578849 SUCCESS，auth原run被G0取消，后续G0 CI36670815592 SUCCESS含P2。P2真实隔离aa0b96471601共137PASS，会员12条浏览器，截图实际查看。
- CE04-G0 auth51f637c支持成长5能力、显式政策Owner，251既有单测+Owner新增1、真实PG+graph5项、SDK/Boot4/package通过。
- CE04-G1 commerce5d78721，auth83e38b2经保留其他任务main变更合并为e23cb52已推送，CI36671965481/36671974503 SUCCESS。411项406PASS/5skip，真实隔离fb6d4a99a58b共151PASS。V53已应用不可修改历史。
- 独立轮转修复commerce45b74ee已正常合并推送，CI36672968400待查。成长UI完整回归曾暴露OrderExpiryLaneTest公平性失败；可控时钟稳定证明领取查询耗尽预算且零尝试时仍推进游标，使热租户先获第二个quantum。修复零尝试耗尽预算不前移，确定性探针旧FAIL新PASS；10项TenantRotation/3项OrderExpiryLaneTest通过。独立提交仅3文件，未夹带G2，见docs/TENANT_ROTATION_BUDGET_FIX.md。
- CE04-G2本地DONE：完整growth-ui-budget-fixed-verify.log共413项408PASS/5既有skip；前端build/Prettier、36项Python、231入口/122能力/34角色、两仓hygiene无阻断。真实--growth --browser rehearsal-324fea9731d5共183PASS，成长11/会员12/目录11/库存9/CATALOG9浏览器检查；当前1440/390政策表单/钱包/列表/冲突/未知/重算/503截图已实际查看。SQL/API确认UI成长250/版本2/账本1，与API路径共6条身份审计；没有重复效果。自有进程/PG/IdP/Vite已finally停止，网络85和数据保留。

## 已修改文件

- auth feat/commerce-growth-ui-contract：成长正式契约G2、231入口bindings+inventory/test计数、governance-p6-rehearsal.py成长浏览器阶段、governance-ce04-growth.mjs、CE04_MEMBER与EXECUTION_PLAN。
- commerce feat/central-growth-ui（已ff合并main45b74ee）：CentralGrowth.tsx、SSO/session/main路由，GrowthActionsController三独立提示、精确中央过滤/Errors/staticGET安全、CentralGrowthMySqlTest第5项/静态壳门禁、CENTRAL_MEMBER_ACCESS与本进度。
- G2产品源码自最终完整回归/浏览器以来无变动；只补证据和状态，待Git交付。

## 未完成

- G2显式路径提交/普通合并推送两仓并查CI。独立修复45b74ee CI待查，不将pending说成SUCCESS。
- 下一CE04-T标签技术细化，已有私有草案auth/.local/governance/commerce-contracts/tag-contract-draft.md，只读盘点未实施。需核对审计目标类型映射（define权限commerce_member但实际字典目标不可冒充会员），正式化T0/T1/T2后串行执行。
- 后续行为/周期/积分、CE05—08未完成。原63节点DAG61DONE/2productionBLOCKED保持，真实OA映射/Owner签字/授权截止/生产SLO RTO/RPO待定仅阻塞对应真实动作。

## 当前问题

- 发券原测试固定20项而实现20项/500ms有界，G1并行演练曾首轮16失败；串行G1/G2最终7项通过，未改发券实现/断言，失败证据保留。订单轮转失败已独立修复，不能混为发券时序问题。
- Docker默认地址池耗尽，P6支持显式RFC1918 /24，不删历史资源。已用81—85，下一核对后可选86；由Docker检查重叠。
- 专用MySQL commerce-inventory-mysql-7841e58190:43308继续运行；source commerce/.local/runtime.env再source .local/central-inventory/owned.env，禁止输出凭据。测试库commerce_test_20260923，V49—V53不可改历史。原8602/commerce_local不切换、不重启。
- OA6385a86既有脏文件不碰。auth其他任务gitignore782ae3d、Docker控制台8b0af28/ffb82a5已在main，原样保留；现有gitignore-hygiene/docker-governance-console工作树不移除。本任务未建新worktree，旧commerce基线工作树保留。
- .local私密证据/截图/配置和停止的PG/IdP/网络/隔离库保留，不提交不删除。hygiene无Java formatter/未配置静态分析限制。旧二进制不认识新族，回退要兼容版本+STOPPED；5秒仅本地准入窗口，不承诺跨库瞬时撤权。

## 下一步建议

1. 完成G2 Git/CI交付，检查当前main是否有其他任务新增提交并正常合并保护。
2. 正式化标签T草案，执行T0协议、T1业务Owner/V54、T2页面，每片验证/进度/Git后继续，不把一片当全部完成。
3. 继续会员其他能力与CE05—08；不重开已完成计划，不重复询问已批准边界。

## 恢复 Prompt

读取CODEX_PROGRESS及auth commerce-readiness/EXECUTION_PLAN、CE04_MEMBER和对应正式契约，从G2交付/标签T继续。边界和Git已授权，无需反复确认；保护原商城/OA/其他任务与私密数据，继续直到已批准计划完成或真实必要阻塞。

最新T0检查点：G2已推送auth8351043/commerce646ebd5，CI36673405496/36673407016待查；独立修复CI36672968400被G2取消（非失败亦非成功）。auth当前feat/commerce-tag-execution，正式CONTRACTS_COMMERCE_TAG.md已细化T0/T1/T2，有限member_tag.read/define/assign与define scope-only已实现。252单元PASS，真实PG f5d5df7c48f4+graph的ExecutionAuthorizationIT6项PASS，自有PG finally已停，tag-core-unit/integration日志和-result.json保留。SDK install→Boot4→package→hygiene正在运行，完成后记录T0证据/Git，再T1；commerce main646ebd5当前无新产品改动，V54尚未实施。

T0最终本地DONE：SDK/Boot4/package/hygiene已PASS，验证后无产品代码变化，准备Git交付；下一T1真实标签Owner/独立族与V54。

## T1进行中检查点（后于上文）

T0已交付auth6bfaae70fa3fb44449f5b502b9ab9278714b8728，CI36673611219运行中；G2 auth36673405496被T0取消，commerce36673407016 SUCCESS（含轮转45b74ee；独立fix CI取消不冒充成功）。auth当前feat/commerce-tag-owner-contract：正式TAG契约追加锁与审计、P6 --tags联调已实现，隐含growth/member/directory/inventory，36项Python/231入口通过，尚未实际运行。

commerce当前feat/central-member-tags（main646ebd5基线）：EmployeeAccess3标签能力/MEMBER_TAG族，EmployeeAuthority全租户/define不可resource且固定实际审计type commerce_member_tag；MemberTagService定义/字典读/分配/关联读的Owner锁/身份幂等/事务审计；精确四HTTP入口；V54已在专用MySQL成功应用不可修改历史；CentralTagMySqlTest新增5项（含64上限/释放、审计失败真实回滚、版本与代际）。SDK来源固定6bfaae7且原安装脚本PASS。无页面改动；完整mvn -B -Pwith-ui verify正在session83658，日志commerce/.local/central-inventory/tag-verify.log，未确认最终测试结果。

下一先完成回归，必要修复后打包真实T1 jar，再在已核对空闲子网10.254.86.0/24执行python3 deploy/governance-p6-rehearsal.py --isolated-identity --identity-subnet ... --tags（先无browser；T2再新增标签页面/浏览器），日志私有。不能并行重复大回归和演练以免发券500ms测试受负载影响；现有growthG2浏览器已183PASS，标签T1不冒充T2验收。完成证据/hygiene/进度/Git/CI后接T2。

T1验证最新：完整tag-verify.log已417项412PASS/5skip（最初4标签项）；后补64上限测试未包含在该次编译，最终tag-limit-test-module.log实际5项全PASS。两次-am窄跑因根POM failIfNoTests=true在无匹配测试上游停止，日志保留；不改POM，先mvn install -DskipTests装当前依赖，再仅-pl commerce-app -Dtest=CentralTagMySqlTest test成功。产品源码自完整回归未改，后补仅测试。两仓hygiene无阻断，36项Python/231入口通过。真实--tags session41176正运行rehearsal-c1893723fb4a（子网86），最新169检查已过标签四次审计与撤权，尚待最终result.json/进程结束。T2草案.local/governance/commerce-contracts/tag-ui-contract-draft.md已准备，未实施。

T1最终本地DONE：真实演练c1893723fb4a已182PASS（tags_checked=true、runtime_switched=false、production_ready=false），自有PG/IdP/JVM停止，子网86/数据保留。完整417项412PASS/5skip+最终标签5项窄测试、36项工具/231入口、SDK/package/hygiene通过。产品代码未再修改，待按明确路径Git交付两仓；下一正式化tag-ui-contract-draft并实施T2，不停止于后端。
