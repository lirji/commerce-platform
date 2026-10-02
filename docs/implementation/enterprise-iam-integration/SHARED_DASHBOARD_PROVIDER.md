# Dashboard 短集合权限共享边界

依赖 Auth `5e7abbf`（接续 `ad09213`）与 Commerce Provider `4ca6acc`；本片只有共享门禁登记，Dashboard 聚合及页面由 CE07 Owner 实施。

`commerce.dashboard.read`：独立 `DASHBOARD` 族、`commerce_tenant` 类型、完整 `TENANT_ALL` 短 60 秒集合引用。无集合 Facts、持久 Source 或隐含任何源读取权。实际聚合源仍各自签独立短引用并应用 SQL Scope。

精确 GET `/v1/admin/dashboard` 及资格 hint `/v1/operations/dashboard/read-access`；hint 不持久化执行来源。V70 仅扩闭集，不改 V67/V68，不自动切换实际租户路由。

验证：私密 Maven reactor install exit0；独占 MySQL 8.4 唯一 schema 上真实 V70 迁移成功，CentralJourneyMySqlTest 8 方法零失败/错误/跳过。不可用该回归冒充 Dashboard 聚合验收。归档 `.local/journeys/dashboard-provider-evidence-v1` 含不变 XML、两个日志及四产品源 SHA。
