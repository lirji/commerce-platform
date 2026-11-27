# 验收证据

| 范围 | 验证方式 | 本地证据 |
| --- | --- | --- |
| 旧运营 29 页 | 打包前端，接本机 8602 真实业务 API。逐页单独重载，检查标题、读取状态、页面宽度与 JS 错误 | `live-matrix.json`、`live/` |
| 平台 3 页及门店经营入口 | 打包前端，在 Chromium 用公开响应结构的独立角色夹具核对 1440、1024、390、320 px | `role-matrix.json`、`role-fixtures/` |
| 中央 36 页与兼容入口 | 通过编译路由与本人导航契约夹具逐页检查。含阶段权限、撤权、原单、预算、发券、活动、目录、表单时间与焦点恢复 | `central-verified.log`、`central-verified/` |
| 表格与筛选 | 对列显隐、密度、全屏返回焦点、批选、剪贴板、日期快捷范围、重置、游标及实际请求参数断言 | `legacy-verified.log`、`legacy-verified/` |
| 构建与静态校验 | 前端构建包含菜单与 TypeScript，另执行菜单单测、改动文件 Prettier、差异空白检查和依赖审计 | `build-final.log`、`menu-catalog.log`、`prettier-check.log`、`npm-audit.log` |

证据位于仓库 `.local/b-console-product-experience/`。真实旧运营检查拦截写请求，中央和角色夹具只验证浏览器契约行为。中央真实 PKCE 与岗位交叉授权需独立身份环境，现有条件用例按配置执行。

固定表头首轮被证实会遮住短表的行内按钮。复验采用长表内部滚动及桌面固定表头，保留原行按钮操作。日期输入的键盘校验验证本机时间准确转换为同一 UTC 窗口。实际数据截图复核了订单编号宽度、商品首列、手机指标区和核心抽屉入口。
