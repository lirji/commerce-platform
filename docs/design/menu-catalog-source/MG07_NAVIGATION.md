# MG07 本人业务导航适配（DONE）

电商新增只读 `GET /v1/operations/navigation`，Auth 生产方固定为 `109b1edb7fb61fa2e2fc2fa0ec726b42438f5810`，由 `scripts/auth-sdk-source.ref` 和既有安装脚本消费。导航客户端只有本人菜单、版本摘要和能力提示；菜单提示不授予业务操作权限。

入口独立缺省关闭，需要 `commerce.iam.store-read.enabled`、`commerce.iam.navigation.enabled` 及受控 `commerce.iam.store-read.configuration`。服务凭据和业务专属 audience 用户证据分别验证，每次按中央租户／主体／成员／代际匹配有效本地 OPERATOR。管理 Token、旧凭据和任意主体 query 均不回退。公开 DTO 遵循 camelCase，Auth 协议遵循 snake_case；精确契约见 Auth 项目 `MG07_CONTRACT.md`。

先通过既有安装脚本核对固定源码，3 项真实模块单测 PASS；完整 reactor 构建 PASS，当前 protocol／SDK、本地 Maven 仓库与 Commerce 嵌套 JAR 逐字节相等。Auth 新隔离 PG／电商新隔离 MySQL／真实 Casdoor PKCE 的 22 项 HTTP 验收 PASS，覆盖正确映射、缺失映射、401、身份和 query 注入、旧代际、跨租户、撤权后投影未就绪503／完成后 NO_ACCESS、断连无缓存回退。Auth281单测和2真PG／图通过。卫生终态无BLOCKING，统一 formatter 工具限制记录；Python／diff检查通过。

私密证据 `.local/menu-role-governance-mg07-fixed-evidence.json`、fixed-sdk／fixed-unit／hygiene-fixed；实际 HTTP 见 Auth `.local/menu-role-governance/mg07-852bc0f01608`。第一次 SDK字节检查错误猜测 groupId路径，改为 POM 真实 `com.lrj.authz` 后检查通过，未改制品。

MG08 将接入侧栏、移动菜单、搜索、默认入口和深链提示。当前MG07没有可见前端改动。没有生产部署，没有改原电商目录、原角色／Grant；演练只写新库，自有进程已停止，证据和失败轮次保留。
