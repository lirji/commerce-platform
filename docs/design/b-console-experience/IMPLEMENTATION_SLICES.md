# B 端实施清单

用户已授权按本范围开始修改并连续完成。一个原目录任务分支 refactor/b-console-experience，串行实施，无新worktree或子Agent。

| ID | 可观察结果 | Needs | Owner/路径 | 验收 | 状态 |
|---|---|---|---|---|---|
| B01 | 刷新恢复会话、深链接及查询上下文 | 现有 /me + 本轮session契约 | frontend shared/session、routeState、App与列表 | 刷新保持身份/店铺/页签/游标；401清除、503重试、退出不恢复；不缓存业务权威数据 | DONE |
| B02 | 平台运维身份与只读指标匹配 | B01 / CONTRACTS第1条 | backend PlatformRuntimeController + frontend角色/健康页 | 平台可登录，仅读平台指标；ADMIN不能读平台、平台不能读/恢复租户；真实HTTP/DB测试 | DONE |
| B03 | 停止工作恢复、结果、审计及历史重放闭环 | B01 / 现有 RuntimeRecovery + EventReplay | frontend RuntimeOperations | 元数据、范围、失败证据、原键未知重试、逐项拒绝、试运行安全门、创建/控制/历史；浏览器与真实API | DONE |
| B04 | 旧工作台及中央员工页完整体验统一 | B01–03 / FRONTEND_ARCHITECTURE | frontend App、Dashboard、shared、CentralShell、各页配方 | 全部B端页面族、详情/编辑/预览/菜单/错误/窄屏覆盖；主题单一，中央不回退旧身份 | DONE |
| B05 | 回归、视觉修订、文档与Git交付 | B01–04 | validation/doc/progress/Git | 构建、适用原E2E、真实DB/API、当前截图实际查看、无溢出/控制台错、Git main正常发布/CI | VERIFYING |

Runtime需求：复用现有本地API与数据库；需要后端新增身份接口时启动自有隔离端口进程；不重启8602。验证凭据写任务私密目录，不覆盖其他任务。无新增基础设施、迁移或生产部署。

完成端点是以上可观察条件和页面族覆盖全部通过，不以自称获奖为验收；每轮审查记录具体偏差及修订。每片验证后更新状态与证据，完整逻辑单元分批提交。

2026-09-30：B01–B04的当前验收、源码摘要和限制见 ../../delivery/b-console-experience/TEST_RESULT.md；B05本地验证完成，远程Git/CI按实际DELIVERY_RESULT闭环。
