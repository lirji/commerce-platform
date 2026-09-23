# 交付计划

唯一设计源：../../design/member-lifecycle-catalog-ui/。原会员经营版本 fa97292 作为已通过验证基线；本任务分支 feat/member-lifecycle-catalog-ui，工作区 commerce-platform-member-suite。

授权：用户明确“先做这部分…补齐…优化”，并确认两项产品选择；AGENTS 授权任务分支、分批提交、必要验证后正常合并推送 main。无需重复请求继续。无生产部署授权；保护原 commerce-platform 工作区两处用户改动。

按 IMPLEMENTATION_SLICES 顺序持续交付；每片维持实现证据和进度，最后更新 API/运行/用户说明、种子、完整构建和浏览器场景、CI。测试不清数据库，新增 UUID 测试租户。经济路径必须真实 MySQL 并发与失败恢复测试。Git 交付由 task-git-delivery 独立处理，业务技能不自行承担推送。
