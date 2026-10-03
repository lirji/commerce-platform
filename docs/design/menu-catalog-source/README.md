# 电商菜单声明与发布候选（MG01）

Auth 权限治理计划的首个产品切片。正式契约由 `auth-platform/docs/design/oa-auth-unification/menu-role-governance/CONTRACTS.md` 维护。

## 单一声明

[catalog.json](../../../frontend/src/iam/catalog.json) 保存真实菜单名称、稳定编码、层级、顺序、路由和能力关联；[navigation.ts](../../../frontend/src/iam/navigation.ts) 生成侧栏／搜索使用的分组和路由集合。协作标题也从同一声明读取。

声明是应用路由配置，不是 Mock 业务数据或用户授权。权限平台已发布快照、实际角色／Grant 和服务端判权仍为治理权威；此切片不按声明授予任何用户权限。

声明保留原 6 组／35 经营入口和商品协作，以及显式遗留目录。新页面可增删，导出不再以固定页面数量作为生产约束；当前壳层只支持经营路由归属有名称的顶层分组，构建先验证该边界。路由改变时保留菜单 code，新增页使用显式新 code。

## 导出

从仓库根目录运行：

```sh
node frontend/scripts/export-menu-catalog.mjs --version 3 --output /path/to/private-candidate.json --current-manifest /path/to/current-manifest.json
```

可用 `--commit` 固定源码版本，默认 HEAD。输入声明从该 Git 版本读取，未提交修改不会被冒充为发布源码。候选关联提交和声明原始字节 SHA256，包含 `auto_grants:false`／`auto_roles:false`；工具不登录、不发布、不写数据库。

当前清单可省略，表示仅生成候选；提供时验证前进版本及原能力语义保留。正式发布仍由 Auth 独立验证，实际接口／资源权限不依赖这个导出结果。

## 当前验证

- 4 项 Node 声明／候选行为测试通过：真实入口、稳定 ID 的路由变化和新增页面、未知能力／环／位置冲突、旧能力语义与非前进版本。
- 新声明生成的 6 组／35 经营路由、名称、排序与 c9eb50c 固定基线逐项相同；商品协作入口和名称相同，无用户可见布局变化，视觉重审不适用。
- TypeScript／生产构建通过；实际 Prettier 执行通过。通用卫生工具无阻断，但其 formatter 自动发现仍报告 TOOL_NOT_AVAILABLE，不能把工具发现限制冒充未执行实际格式化。
- Git 固定版本 CLI 导出、Auth 消费方和远程 CI 的最终回执由 Auth MG01 验收和本地根进度记录；不声称已部署。原本机目录、角色、Grant 和数据库未修改。

固定提交 `b109f2092aba670515ba33a537289346b5ccfa92` 的真实CLI与Auth导出器生成相同候选（44菜单／123能力）；未请求发布API。两工具28个Python／4个Node行为测试、共享声明构建校验和最新构建PASS。远程CI另行追踪，不用本地通过冒充远程结果。

远程37100533319验收暴露Node JSON导入属性缺失；navigation.ts已补type=json，构建和Playwright真实测试发现PASS，修复提交47ebb6526af7707468d72c2d84bf515d579669b2已正常合并／推送远程main，精确CI37100968743 SUCCESS（构建、真实MySQL、声明行为测试及Playwright等必需步骤通过）。本切片未部署或调用发布API。
