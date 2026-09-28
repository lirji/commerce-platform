# S-VR-01 实现证据

本轮实际应用更新后的共享 `frontend-architecture-design`、`frontend-implementation` 和视觉指导；实施者为 Codex，没有声称 Claude 原生调用已被验证。用户授权改当前项目，偏好为简洁企业工作台与重图片展示；具体颜色、尺寸和当前页面仍属于可调整设计决策。唯一权威为 [前端架构](../../design/unified-commerce/FRONTEND_ARCHITECTURE.md)。

## 当前实现

- `theme.ts` 统一公共颜色和 Ant 主题，状态采用成对的深字浅底；CSS 不再重复声明另一份色板。
- 共享壳层使用线性 SVG 导航图标、236px 侧栏、64px 上下文条和清晰页头；保留认证、门店、权限、路由与功能搜索。
- 经营总览保留真实统计和每日趋势，弱化装饰，将长数据口径收进可展开说明。
- 共享列表、字段与弹层调整留白和字号；商品操作明确列宽、不折行，宽表在自身容器滚动。移除旧首列最小宽度，避免勾选列占据 160px。
- 商城采用图文商品卡片、44px 购物动作、明确缺图提示与简短服务说明；商品图片、规格、价格与报价来自真实 API。保留包装示意图原始 alt，没有生成假商品照片。
- 地址必填错误使用中文；购物袋、优惠、积分、订单提交和幂等逻辑没有改动。

## changed_paths

产品文件：`frontend/src/theme.ts`、`frontend/src/style.css`、`frontend/src/shared/Icon.tsx`、`frontend/src/shared/ui.tsx`、`frontend/src/app/App.tsx`、`frontend/src/features/Dashboard.tsx`、`frontend/src/features/ProductOperations.tsx`、`frontend/src/features/Shop.tsx`。

相关验证：`frontend/tests/dashboard.spec.ts`（纠正历史“深色”名称）、`frontend/tests/visual-refresh.spec.ts`（真实窄屏/焦点/报价）、`frontend/scripts/capture-visual-refresh.mjs`（可复用取图）。相关文档：README、唯一架构视觉章节、当前 BRIEF/切片、当前 Evidence/Review/Test Result、进度与交付记录。截图只显式纳入本轮选定文件；`.local`、凭据、后台配置和其他工作树不纳入交付。

## 浏览器过程与修正

旧实现参考为当前运行截图，基线源码为 `f170e317f12006d8559ac41d1858b00c42998890`。本次实际查看旧 `dashboard-desktop`、`catalog-desktop`、`shop-desktop`、`shop-mobile`：发现重边框、实色标签、管理台式商品排版、大首字伪图片、32px 购买动作和手机冗长说明。

先完成经营总览与共享壳层，实际查看代表阶段总览桌面/手机、商品列表及字段错误，再扩展商城。代表截图发现操作换行，修正列宽与操作容器后重新取图；原样张保留用于解释修正，不作为批准的视觉回归基线。

取图过程中还修正了测试工具：等待窄屏重排，普通页面回到顶部，固定弹层使用 viewport 取图并补充内部滚动状态。这样不会把 Ant 瞬时测量或截图中的悬浮顶栏当成最终页面溢出。最终全部 21 张当前截图已由本次 Codex 用 `view_image` 实际查看，详见 [REVIEW](../../evidence/frontend-visual-refresh/REVIEW.md)。

## 版本与运行事实

最终 8 个产品文件的有序 SHA-256 清单聚合摘要为 `3a72634e2bfdbf5b15cd07377373dc6bb281b4013ff2d82e506b13329e589876`；单文件摘要、route、视口、状态和时间在 `docs/evidence/frontend-visual-refresh/after/capture.json`。截取结束再次校验源码未改变。

本轮预览为 `http://127.0.0.1:8601`，真实本地 API 为 8602。沿用现有依赖与 Chromium，不安装组件库或字体。Vite 代理仅在本次启动参数指向 8602，仓库默认运行配置没有改变；现有后台进程未重启。

本地原测试凭据属于已停止的环境。使用三个原种子脚本的私密副本，只调整根路径和输出凭据路径，在 `commerce_local` 建独立新租户，业务仍通过真实 API 持久化。原 `.local/*-access.json` 未覆盖。既有测试的私密副本仅将凭据 URL 指向这些输出；断言没有放宽，源码/副本清单保存在 `.local/frontend-visual-refresh/test-adapter.json`。

## 实现交接

`S-VR-01`：实现完成；真实构建、26 项浏览器回归与实际截图已完成。适用最终验收由 [TEST_RESULT](TEST_RESULT.md) 归一化，进度 Owner 再推进 DONE。没有新 API、数据库迁移、远程部署或性能承诺。质量门禁的工具识别/单元测试限制在 TEST_RESULT 逐条保留。
