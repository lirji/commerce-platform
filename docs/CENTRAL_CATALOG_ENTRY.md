# 中央CATALOG页面接入

固定入口 `/operations/catalog?tenant_id=<中央组织UUID>&store_id=<门店编号>`，需要构建时开启既有VITE_IAM_ENABLED、配置既有OIDC issuer/client，以及后端P6中央CATALOG接管条件。只增加静态路由，不开通任何权限或迁移原商城。

页面复用ProductOperations、CatalogMerchandising、CatalogScheduling，通过React RequestContext注入本页面的中央Bearer与X-Tenant-Id；旧控制台仍使用原客户端。门店编号/URL只是查询上下文，后端每次按真实门店Owner判权。CATALOG不会隐含store.read或商品导出，没有授权门店自动选择器。

本轮本地Java393项（388通过/5可选跳过）、类型检查/构建/Prettier通过；auth仓governance-p6-rehearsal.py --browser --isolated-identity执行34项检查通过，包含真实商品编辑、跨店/跨租户、退出/401/过期、源撤权403与依赖503。1440和390视口及桌面编辑Modal截图已查看；补充演练90089b33648d已从空会话真实输入密码、完成PKCE无client secret兑换，并保持组织和门店回跳；9条浏览器分项通过。视觉证据在auth仓.local/governance/p6/rehearsal-c9a502b7a77c，最终含PKCE的34项在rehearsal-90089b33648d；页面源码相同。

扩展会员/营销/库存/交易等能力契约以auth仓docs/design/oa-auth-unification/CONTRACTS_COMMERCE_EXPANSION.md为审阅草案；未发布、未将旧ADMIN自动授予中央权力。原商城运行实例未切换。
