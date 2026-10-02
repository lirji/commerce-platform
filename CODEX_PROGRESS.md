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
