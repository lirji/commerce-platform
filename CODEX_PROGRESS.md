# Codex Progress

## 任务目标

先提交推送阶段7到远程main（已完成），再按用户确认方案完成阶段8持久营销Journey。用户最新AGENTS#8持续授权：必要验证通过后，独立分支、完整逻辑提交、正常合并推main，无需重复确认。原方案未明确授权不commit/push条款已由此满足；无生产部署授权。

## 已完成

- Phase7四提交11c7f37/a6b3b8d/40376b3/aa8bef1，实际remote main aa8bef17343c9722b3d2f19a4f9d9a006d694ae1；不要重复。
- 当前feat/phase8-marketing-journey。S1–S5实现/本地验收全部DONE：图稳定校验/发布固定引用重检/纯preview、V44逐步历史、显式动作registry、失败原版本保护、history tenant/本人权限、V45有界到期查询与真实backlog。
- 真实支付事件→WAIT→当前事实→CREDIT→END→AVAILABLE/ledger1；不同eventId同order来源去重；WAIT后真实标签撤销false无grant；v1/v2/pause固定历史通过。
- 实际25秒WAIT SIGKILL重启、两个新JVM到期竞争；动作write/stepFinish前真实kill+owned MySQL连接释放，全部回滚、另一JVM恢复唯一grant/ledger。隔离recovery schema，所有临时trigger/owned进程已清理。
- OLD远程main aa8bef1与NEW共享V44/V45互读、去重、恢复PASS；旧trace明确PARTIAL/LEGACY_PARTIAL，不伪造。
- 最终clean scripts/build.sh PASS：app297/0/5 configured skips、architecture3、marketing27/order45/kernel3；前端tsc/Vite/UI入jar。jar SHA256 74a16e41e86ff98928fc00ef09444dca097cb845bc735a535c4e2113b6c9baa9。
- 三项mutation被具体断言捕获，源hash恢复后上述clean通过。hygiene无BLOCKING，npm audit零漏洞，diff check PASS。
- 规模10000=469.990s；干净新tenant复测100=6.127s、1000=48.759s；50k future无推进；hot5000/normal5=0.726s正常租户先完成，真实payment/event同时推进。
- affected browser4/4；修复四项旧UI断言后最终jar全browser24/24（1.2min）。旧main CI20/24四项失败为陈旧按钮/统计/主题断言，不是新Journey故障。
- 独立worktree.local/phase8-ci-fix分支fix/ci-browser-contracts提交bdc2af712b7e76639cab4ac8e43c65398ea764c1已推远程，CI run36362758041在执行。无前端产品变化。
- 正式设计/契约/切片、TEST_RESULT S1–S5、Review/QA、00–16证据、矩阵、容量/SQL计划、可复跑脚本、PHASE8_REPORT已归档。报告严格PHASE_8_COMPLETE_WITH_LIMITATIONS，本地容量/历史Facts/保留期等限制明示。

## 已修改文件

- JourneyApi、JourneyGraph/Actions（新）、JourneyService、JourneyMapper/XML。
- V44/V45、JourneyController、精确SecurityConfiguration路径。
- JourneyGraphTest/JourneyRecoveryTest（新）、PersistedCommerceTest。
- docs/delivery/phase8-marketing-journey、docs/evidence/phase8-marketing-journey、doc-map、CODEX_PROGRESS。
- 独立fix分支frontend/tests四个陈旧契约断言和自身DELIVERY_RESULT；待正常merge到本任务。

## 未完成

- 分逻辑提交阶段8功能与验收证据；合并独立CI测试修复，push feat分支以不可变ref等待实际远程CI。
- 远程CI绿后正常merge到main并push，ls-remote核实。
- DELIVERY_RESULT/CI_RESULT与状态/报告末尾补真实Git结果，最终main提交远程CI完成后汇报。

## 当前问题

- 无本地必需验证阻断；远程CI未完成不能虚称绿。
- 原首轮1000容量受故障trigger/DDL互扰，保留原始结果但不用作主容量；已隔离recovery并干净复测，不重置/replay成功数据。
- 生产SLO/保留期无产品依据；无百万timer认证；历史不存完整Facts，OLD覆盖partial。

## 下一步建议

1. 核对允许路径/源码指纹，提交完整功能单元和独立证据单元，不机械按文件类型拆分，不夹带其他任务。
2. 正常merge fix/ci-browser-contracts，push feat，用gh核对当前不可变SHA CI成功；失败按具体证据修复，不绕过门禁。
3. main未配置保护（已查询），仍必须正常merge/push不force。核对remote main实际SHA。
4. 最终状态/CI与Git结果落盘，关闭owned进程后给用户简短报告与main SHA。

## 重要上下文

原方案/Users/liruijun/Downloads/phase-8-marketing-journey-workflow-orchestration.md。用户已确认DELIVERY_PLAN并要求连续执行；最新AGENTS#8是Phase8 Git持续授权来源。不派子Agent。
.local/runtime.env/tokens私密不提交不打印。使用既有dev_infra MySQL8.4，commerce_test_20260923/commerce_phase8_bench/commerce_phase8_recovery均隔离本地schema，无生产部署。初始ROUTER_CONTEXT仅保留启动历史，不代表当前缺实现；后续权威formal TEST_RESULT已PASS。

## 恢复 Prompt

读取CODEX_PROGRESS、阶段8DELIVERY_STATUS与git实际状态，从未完成Git/远程CI继续。阶段7已推main，阶段8全部本地实现/验收已完成；不要重跑已通过大规模测试或重规划，不等待继续，不把远程pending冒充PASS。正常提交合并推main并归档实际结果，禁止强推/生产部署。
