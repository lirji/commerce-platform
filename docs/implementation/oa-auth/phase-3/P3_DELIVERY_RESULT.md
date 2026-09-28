# 商城 IAM P3 交付记录

商城P3实施与验收完成。任务分支feat/oa-auth-p3-scope在原目录开发；55578e0实现、525bc7a脱敏故障证据，正常合并推送main dceeb5ad16bc2e55d8787d968b28006e043a8485。无强推、重置、生产部署。

[该代码提交CI](https://github.com/lirji/commerce-platform/actions/runs/36440692582)结论SUCCESS：固定auth SDK637385b取源安装、真实MySQL构建验证、前端依赖审计、打包启动、真实浏览器业务验收全部通过。本文件及进度更新为后续文档提交，未改应用、迁移、SDK ref或CI配置；其自动CI实际结果以该提交的Actions检查为准，不混称为上述run。

本地完整383项零失败（5既有条件跳过），6项新MySQL用例与48项跨进程HTTP通过，见P3-02_TEST_RESULT及evidence。实际API范围和限制见RUNTIME_AND_CONTRACTS。

跨仓依赖顺序为auth先推送（源码637385b已在auth main可达），商城后推送；两仓不共享数据库写入权威。auth首轮CI的Linux测试事件纳秒精度问题已修正于e55368a；后续CI及整个P3最终暂停状态统一见[auth P3交付报告](https://github.com/lirji/auth-platform/blob/main/docs/implementation/oa-auth/phase-3/P3_DELIVERY_RESULT.md)，本记录按商城代码交付时点记录，auth最终结果由该权威报告维护。

没有新增worktree。原commerce P0 detached工作树（auth/.local/p0-baselines/commerce）和忽略的.local、构建产物、历史验收文件保持；.local/oa-auth-p3含0600配置与自有MySQL证据需保留供复验。target、缓存及构建目录可重建，但没有清理授权，本轮未删除。OA用户修改未提交。按用户停止点，本仓不进入P4；整体恢复先读auth权威PROGRESS_STATE。
