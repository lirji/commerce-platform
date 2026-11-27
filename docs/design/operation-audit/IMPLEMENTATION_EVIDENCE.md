# OA-01 商城类目停用验收

- 入口为平台工作台「平台工具 / 操作日志」。使用平台类目维护专岗停用隔离类目，在日志中按对象编号查找，打开差异并返回原类目。独立平台读取身份可查，租户管理员读取返回 403。
- `OperationAuditMySqlTest` 以真实 HTTP 与 MySQL 验证四次尝试、一次变更、同键重试、游标、越权、敏感原因遮蔽、写入故障回滚及提交后断连。`StoreOpeningMySqlTest` 验证已有开店、目录和分类行为。
- 隔离环境从空库迁移至 V85。另用 V84 样本库验证升级，旧审计记录仍在，V84 应用镜像在扩展结构上可重新启动。证据在 `.local/operation-audit/mysql-tests.log` 和 `.local/operation-audit/upgrade.log`。
- 前端类型检查和构建通过。Playwright `operation-audit.spec.ts` 使用隔离库签发的专岗登录并执行真实停用。实际检查桌面列表、详情和 390 像素宽的详情截图。结果与截图位于 `.local/operation-audit/`。
- 运行环境需要执行 V85。生产容量与三方同单联查按后续切片验收。本片审计策略只记录策略版本，实际归档和清理在 OA-08 至 OA-10 放行。

## OA-02 分类字典与开店

- 隔离 V85→V86 后，`OperationAuditMySqlTest` 和 `StoreOpeningMySqlTest` 共 7 项通过。改名和排序一起提交形成 1 份效果及 2 个字段差异。开店记录包含草稿、补正、开通及实际经营类目，跨租户和会员读取被拒绝。
- 3 项模块边界检查通过。前端类型检查和构建通过。Playwright 实际在店铺管理展开开店记录与字段差异，并复查 390 像素视口。日志及截图位于 `.local/operation-audit/oa02-*`。
