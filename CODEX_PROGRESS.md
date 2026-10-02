# Codex Progress

## 任务目标

B端接口匹配、刷新恢复及Awwwards/Webby/FWA品质追求，持续完善前端。当前第二轮Craft；允许任务分支正常合并推送main，无生产部署；不把测试通过当作外部获奖证明。

## 已完成

- 上轮接口/刷新工程已交付f355c28，原证据保留docs/delivery/b-console-experience/。
- C01–C05产品实现与自查修订完成：统一壳层、真实正负趋势、SKU操作/详情、中央lazy加载、只读旅程关系图。
- 构建/格式通过；Chromium19、Firefox/WebKit22、真实业务19、真实HTTP18通过；29管理和17中央入口及手机/详情实际截图复查。
- 设计/实施/测试/修订证据：docs/design/b-console-craft/PLAN.md及docs/delivery/b-console-craft/。

- 四次逻辑提交正常合并推送main；产品HEAD 5009180完整CI成功：后端470例（5跳过）、浏览器41通过（1条件跳过）、0失败。CI_RESULT/DELIVERY_RESULT已记录。

## 已修改文件

- frontend/src/{app,features,iam,shared}相关B端页面与通用壳层、theme/style/workspace/main。
- frontend/tests/{b-console-craft,central-workspace,dashboard}.spec.ts。
- docs/design/b-console-craft/、docs/delivery/b-console-craft/、docs/PROGRESS_STATE.json、README.md、docs/doc-map.md、本文件。

## 未完成

- 产品范围无未完成项；最后报告HEAD的CI实际结果以 .local/b-console-craft/ci-final-status.json 和远程精确HEAD为准。失败时从该处恢复。

## 当前问题

- 无当前功能/布局阻塞；外部评委获奖认可未验证，不能保证。
- 原8602容器未重建；8611/8613源码预览，8614生产构建预览；8612为隔离API。勿停止用户旧实例。
- 私有凭据、截图及原始日志在忽略.local/b-console-craft/；非正式生产部署。

## 下一步建议

1. 核对最后报告HEAD CI；若已成功且工作区干净，本轮无需继续修改。
2. .local/b-console-craft/ci-final-status.json绑定最后报告HEAD和实际结果；失败时修复实际失败，保留既有证据。
3. 核查工作区/旧工作树，不丢弃用户内容，不强推。

## 恢复 Prompt

请读取CODEX_PROGRESS.md和docs/PROGRESS_STATE.json，从Git/CI交付继续，勿重复实施已完成切片，不等待继续；外部获奖不是已验证事实。

## 原权限扩展目标恢复：CE05-A1（2026-10-01）

- 目标保持：完整商城角色权限与auth接入项目菜单资源展示，继续原CE05—08计划，不能把单片当全目标完成。
- 旧暂停条件已解除：Craft另任务已完成，本仓main86acfb0干净。本轮原目录串行feat/central-audience-operations，auth原目录feat/commerce-audience-owner-rehearsal；无新工作树/子Agent。
- auth A0精确CI36707598359 SUCCESS（4747ac49）已核验；SDK来源固定该版本，sdk-install通过。
- A1人群集合read/create、AUDIENCE路由、稳定主体命令及头/成员/审计同事务已实施；V63已在专用MySQL执行，不可修改。兼容夹具修正后，7专项全部PASS；最终完整回归477项（472 PASS/5既有skip）、构建及7源码摘要核对PASS，日志owner-verify-corrected.log。独立跨进程edc3a5f8c7d0/子网115共549PASS，152类/迁移及演练制品摘要一致，自有进程已停止。本地A1 DONE，GitCI待交付。
- 未完成：A1 GitCI交付、A2员工页；其他CE05—08与原生产目标/Owner/部署授权HOLD仍在。
- 专用commerce-rules-mysql-698708fb5f已恢复启动，卷/43308保持；先source .local/runtime.env，再source .local/central-inventory/owned-rules.env。不得清空数据或修改V49—V62。
- 下一步：运行既有验证与真实隔离演练，记录正式结果后持续A2及后续。保留Craft与其他证据及原8602，不反复等待继续。


## CE05-A2 人群员工页（2026-10-01）

- A1已推送1987062，精确CI36952128923 SUCCESS。Auth b5a6c64首次CI计数基线259/实际260失败，本地汇总漏判；2445da5已修，9契约/28工具通过，精确CI36952452483 SUCCESS已核验。
- 当前原目录feat/central-audience-entry，基线1987062；Auth feat/commerce-audience-browser基线2445da5。无新工作树/子Agent，不改旧迁移/原8602/OA。
- 已实施：AudienceActionsController/独立create-access、CentralPageController静态入口、中央路由、导航/lazy页；audienceClient白名单；CentralAudiences实际目录/两Tab/新鲜度说明/0—500成员输入与重复纠错/时间24小时/409/原键体冻结/切Tab与取消退出/401卸载/403和503隐藏写入及重新核验。
- 8真实MySQL专项PASS，首次UI构建PASS；最终完整回归.local/central-audiences/ui-verify.log已BUILD SUCCESS：478项473PASS/5既有skip；最终UI重构建/forceCreation package及155类/迁移/45资源/演练复制JAR核对PASS。Auth脚本接线/262入口/9契约/28工具/语法通过，真实--browser session5093/569aae3f8c33/子网116运行中，待检查点与截图复核。
- 修改文件：commerce-app下AudienceActionsController、CentralPageController、CentralEmployeeConfiguration、CentralAudienceMySqlTest；frontend/src/iam下CentralAudiences、audienceClient、CentralProducts、navigation；本进度。无新增迁移/SDK变更。
- 未完成：真实人群PKCE/业务浏览器与精确SQL/截图（1440/390）、最终版本复核、正式结果及GitCI；完整478回归、构建/制品及hygiene已通过，A2仍不能标DONE。其余CE05—08及auth资源展示继续。
- 下一步：先核验Auth侧session5093/569aae3f8c33真实--audiences --browser检查点及结果；不重复启动已完成478回归。脚本完成后实际查看1440/390页面和关联表单/异常截图，确认质量/精确SQL与版本摘要，再GitCI交付并继续其余CE05—08。

- 最新真实演练569aae3f8c33（session5093）已147检查点PASS，仍在运行；先检查同一句柄/OS进程和检查点，不重复启动或将观察超时当终止。


### A2首轮失败与最终演练恢复

- 首轮569aae3f8c33/子网116已终止exit1，594检查点通过；人群write-only/read两阶段真实PKCE、必填/重复/501/时间/409、丢已提交响应后原键体重试、切Tab/取消退出/空成员和1440/390目录已执行通过。不能把未走到的最终SQL5审计、401与503当PASS。
- 失败为测试initScript每次导航重写Token，覆盖刻意注入的无效凭据；已参考既有规则脚本保留已有session，401断言不变，未改业务/权限。截图捕获另归零滚动、弹层用实际视口并增390弹层，避免fixed头部/遮罩的整页捕获伪影。
- 9契约/28工具、脚本语法及hygiene重验通过；commerces8源码和最终JAR摘要均不变，不重复478回归。首轮证据/数据保留，自有进程正常finally停止。
- 最终真实--audiences --browser演练Auth侧session18603/.local/governance/p6/rehearsal-a5fd3867e690/子网117正在运行；原5093已停止，当前恢复先检查18603/PID及检查点，不因观察超时重启。A2保持VERIFYING，Git未交付。
- 下一活动片只读预分析：原CampaignService八独立HIGH动作与budget.read同campaign资源，TENANT_ALL；现有DRAFT/IN_REVIEW/APPROVED/REJECTED/PUBLISHED/PAUSED、固定引用新鲜度/唯一发布、content version与lockVersion分别维护，预览无预占，预算履约内部入口不由员工撤权取消。A2交付后细化技术切片，不提前改代码。


## CE05-A2 最终本地验证与交付检查点

CE05-A2本地DONE：固定人群目录/创建两Tab与独立创建提示；8项真实MySQL和完整478项（473PASS/5既有skip）、最终前端build/forceCreation package通过。最终真实a5fd3867e690（10.254.117.0/24）647检查点PASS，人群11条浏览器检查及全部既有员工页回归通过；恰5条实际身份审计，UI两个快照为1:2和1:0，原键不重复、导入不创建客户。1440/390表单/目录、409/未知/退出确认/成功/401/503共11张截图已实际查看，正文390且表格内部横滚。两仓源码摘要、17嵌套模块/661类/45资源及复制JAR一致；262入口/122能力/34角色、9契约/28工具及两仓hygiene无阻断。首轮594后401夹具覆盖凭据失败已修正并保留。自有进程已停止；无新迁移，V49—V63不可改、SDK固定4747ac49，原8602/OA不切换。Git/CI待交付；下一CE05-CAM活动/审批/预算细化，其余CE05—08及auth资源展示未完成，生产2HOLD不变。

下一步：正常任务分支提交、合并推送main并核对精确CI，然后串行CE05-CAM0/1/2。原18603已exit0，不再启动A2演练；证据a5fd3867e690/result.json与11张已查看截图保留。


## 当前IAM交付与下一切片（2026-10-01）

CE05-A2完整交付DONE：auth aa117462c5eed9242c86bb24dc1b81c4feb556df / commerce51c771b336adc348007b1fad122f7632b4f576d8均正常推任务分支及main，精确CI36954769355/36954777665 SUCCESS已核验。647实际检查点/11人群浏览器/11已查看截图与478回归证据保持；首轮594后401夹具失败记录保留。下一CE05-CAM0已在auth提交b311e4c并正常推main，精确CI36955364612 SUCCESS已核验；商城CAM1仅完成源影响分析，未改产品代码。

CAM1准备分支feat/central-campaign-operations（基线51c771b），尚无产品修改。先核对auth CI36955364612，再用SDK b311e4c实施。源影响分析保留auth .local/governance/commerce-contracts/campaigns-owner-impact.json：新增CAMPAIGN族/9独立能力，活动实际正内容版本、原状态锁分离、版本化同事务身份审计；V64可用性实施前核对，已应用V49—V63不可改。旧订单预算履约及原8602/OA、Craft保持。全目标仍active。


## CE05-CAM1 实施中

- CAM0协议b311e4c精确CI36955364612 SUCCESS，SDK已固定该完整提交。
- 原目录feat/central-campaign-operations实施：9有限CAMPAIGN能力、6实际版本动作、独立预算列表、V64版本审计与原回执前路由/业务锁/期限校验。新迁移尚待真实MySQL应用及检查；V49—V63不改。
- 当前源码已修改，未编译/验证，不标DONE；下一步专项真实MySQL（权限独立、内容版本/锁版本、回滚、原键、停止、401/503、预览无写与旧订单履约）以及跨进程与全仓回归。全CE05—08/资源展示仍active。


CAM1恢复实时记录：完整489/11专项与当前制品字节栅栏PASS；真实跨进程session88447/PID34190/rehearsal-47a2d78d80c5/子网118仍运行，先检查同一句柄及检查点，不因观察超时重启。source-fence.json已核验演练复制JAR与验证制品889b6fd5一致；源码10摘要未变。CAM1仍VERIFYING，尚未Git交付；CAM2仅读影响分析保留auth .local/governance/commerce-contracts/campaigns-ui-impact.json，待CAM1实际验证/GitCI后细化实施。全目标不缩小，原8602/OA/旧迁移/数据及其他任务证据完整保留。


## CE05-CAM1 最终验证与交付检查点

CE05-CAM1本地DONE：9独立活动/预算能力、6实际正内容版本动作、独立目录/预算与真实预览已接入；V64版本审计与活动/预算/状态/原回执同事务。11真实MySQL专项（含81权限HTTP边界/实际SQL故障/锁等待期限）、完整489项484PASS/5既有skip、9契约/28工具及两仓hygiene无阻断。最终10源码/SDK摘要与17嵌套模块/661类/510资源/45前端文件/复制JAR一致。真实47a2d78d80c5/子网118已exit0，645检查点PASS（79活动标签），SQL再次核验13身份审计/实际内容版本1:3、7:6、8:4，v7 PAUSED/lock6、v8 PUBLISHED/lock3，客户订单在STOPPED后正常释放v8预算。自有进程/PG已停止，数据证据保留。Git/CI待交付；CAM2和其余CE05—08/auth接入资源展示未完成，原8602/OA及生产2HOLD保持。

下一步：按task-git-delivery显式路径提交本仓CAM1改动和auth演练/验收记录，正常合并推main，核对精确head CI；不重复已终态88447/47a2d78d80c5演练。CAM2准备记录campaigns-ui-impact.json只有仅读分析，待CAM1 Gate/GitCI后细化真实提示契约与页面，继续完整CE05—08目标。
