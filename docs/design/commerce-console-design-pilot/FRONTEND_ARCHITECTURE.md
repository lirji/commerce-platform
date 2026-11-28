# 交易中心 B 端代表页面设计

## 现状和调研取舍

基线为 `da5adb7`。已有 React 19、Ant Design 6、Vite、工作区壳层、统一时间和可配置业务表格。主要差距来自页面内部的任务优先级。基线在 1440、1920、2560 px 采集六类页面，共 18 张截图。

| 来源 | 核对内容和本项目借鉴 |
| --- | --- |
| Art Design Pro | 阅读电商工作台和 ArtTableHeader 源码，访问在线演示与其预览资料。将浅色壳层、连续的表面和清楚的页头带入简约方案，指标文案保留业务口径。[电商工作台源码](https://github.com/Daymychen/art-design-pro/blob/f3aaf58ee/src/views/dashboard/ecommerce/index.vue) · [演示](https://www.artd.pro/) |
| Tabler | 核对在线 Dashboard 截图、表格 Sass 和 ApexCharts 样式。把数字、对比口径和趋势安排在同一阅读路径，保留图表键盘和明细表入口。[演示](https://preview.tabler.io/) · [表格源码](https://github.com/tabler/tabler/blob/63770c495/core/scss/bootstrap/_tables.scss) |
| Medusa | 阅读订单列表、产品列表、订单详情，其中详情按商品、支付、履约、客户和权限分区。参考独立业务区块，在本项目纵向组织订单详情。[订单详情源码](https://github.com/medusajs/medusa/blob/01f39c444/packages/admin/dashboard/src/routes/orders/order-detail/order-detail.tsx) · [商家订单指南](https://docs.medusajs.com/user-guide/orders) |
| Shadcn Admin | 实看在线 Dashboard，并阅读任务行操作和工作台组件。采用统一按钮基线、明确的菜单触发器和紧凑表格，保留操作可发现性。[演示](https://shadcn-admin.netlify.app/) · [任务行操作](https://github.com/satnaing/shadcn-admin/blob/140cb9569/src/features/tasks/components/data-table-row-actions.tsx) |
| Saleor Dashboard | 核对仓库官方订单截图、OrderListPage、OrderDetailsPage 和 ProductListPage。将交易身份、货品行和关联活动分开，以独立表头呈现。[仓库及订单截图](https://github.com/saleor/saleor-dashboard/tree/3392f622e) |
| Vue Vben Admin | 核对 README 工作台截图和 Ant Design 的 Analytics、Workspace 源码。采用任务和分析两种清晰入口布局，导航的语义沿用现有任务族。[Analytics 源码](https://github.com/vbenjs/vue-vben-admin/blob/bf512713d/apps/web-antd/src/views/dashboard/analytics/index.vue) · [演示](https://www.vben.pro/) |
| Refine | 从 Awesome Refine 选 Finefoods Ant Design，实看演示并核对 Dashboard、订单列表源码。商品和订单表显示可执行的查询与行操作，日期条件和结果范围保持相邻。[示例目录](https://github.com/refinedev/awesome-refine/tree/f228fc64d) · [Finefoods 演示](https://example.admin.refine.dev/) · [订单源码](https://github.com/refinedev/refine/blob/2352eb5b6/examples/finefoods-antd/src/pages/orders/list.tsx) |
| Shopify | 核对官方订单查询与资源列表视图。采用面向任务的服务端状态筛选与可清理条件。[订单筛选](https://help.shopify.com/en/manual/fulfillment/managing-orders/viewing-orders/filtering-orders) |
| Stripe | 参考 Dashboard 官方说明中的对象导航和支付检索，让「订单」「支付结果」维持清楚的边界和入口。[Dashboard 查询](https://docs.stripe.com/dashboard/search) |
| Linear | 官方设计文章及截图把层级、对齐、高密度和浅色表面落到布局。用于运营队列的紧凑阅读节奏。[设计过程](https://linear.app/now/how-we-redesigned-the-linear-ui) |
| Vercel | 参考 Geist 对主题和产品组件的一致定义，保留本项目语义 token 作为颜色和组件规格来源。[Geist](https://vercel.com/geist/introduction) |

公开演示、第一方文档图和源码的访问结果以[验收记录](../../delivery/commerce-console-design-pilot/DELIVERY_STATUS.md)为准。MIT 仓库许可证和 Medusa 开放核心许可声明已核对，Refine 示例和 Awesome 列表仅作观察。实现为项目自有组件和布局。

## 角色、路径和数据

| 角色和任务 | 当前页面问题 | 试点路径与完成标志 |
| --- | --- | --- |
| 运营查看某门店经营，再处理异常 | 摘要、异常和趋势沿用同一节奏。待办第一行三项，第四项另占一行，图表阅读开始较晚。 | `#dashboard?design=…`。在比较页切换三种任务优先级，核对统计范围、趋势指标与日期，打开异常筛选或跳转商品。 |
| 运营查单和处理支付异常 | 查单、快捷状态、表格设置和两种详情入口同时抢占视觉层级。店铺名与编号挤在同一列。 | `#orders?design=…`。在原服务端筛选和游标语义下完成查找、预览、打开完整详情再恢复列表。 |
| 运营核对订单 | 商品明细在通用事实之后，后续处理入口与明细阅读割裂。 | `#orders?order=…`。从行点进完整详情，核对订单身份与商品、查询支付证据、访问已有履约/售后/退款队列。 |
| 运营管理商品 | 操作、筛选与同一表内多类对象叠放，活动店铺的数据边界不突出。 | `#skus`。从门店上下文进入规格和商品，按服务端条件筛选，进入调价/上下架和更多菜单。 |
| 运营创建活动草稿 | 活动身份、优惠、日期和规则在一个线性表单中混排。 | `#campaigns` → 新建活动。按活动与有效期、优惠与资格、商品范围与阶梯、人群与预算定位配置。保留草稿校验和离开确认。 |
| 商家操作员经营授权商品 | 门店上下文集中在顶栏，商品表复用平台展示，同一门店信息占表内空间。 | 以 OPERATOR 登录 `#skus`。在标题下确认当前授权门店，再完成规格查找、状态筛选、检查编辑和渠道价入口。入口继续由原授权 API 决定。 |

## 同一业务的三种浏览方向

| 方向 | 工作台 | 订单 |
| --- | --- | --- |
| 现代简约 `minimal` | 一个连续指标条，趋势在主阅读位置，待办和经营规模下移，减少首屏边框和视觉权重。 | 查询与结果连续，常规行距和较清楚的字段间隔，详情保持直接入口。 |
| 高密度运营 `operations` | 先看异常队列，以横向短指标和短趋势扫读当前进展。 | 模块化快捷队列直接应用到服务端，紧凑默认行距，状态、金额和支付截止在列表中独立呈现。 |
| 数据驱动 `analytics` | 时间窗、焦点指标和趋势集中，七天对比贴近日线。异常与规模放在趋势下方。 | 在列出记录前显示当前批订单状态组成，图例提供同一状态查询。条数只描述当前返回批次。 |

切换只改变可分享的 `design` 参数，原路由的店铺、查询、游标与详情参数沿用。两个比较入口各自显示明确的退出入口。运营列表来自 `/admin/orders`，工作台来自 `/admin/dashboard` 和已存在的四个异常查询。订单组成按响应记录计数。商家店铺来自 `/operations/stores`，商品来自现有经营接口。交易和权限仍由服务端负责。

沿用现有组件和权限壳。试点范围是旧运营工作台和订单的三方向、商品和活动操作及订单完整详情。中央员工总览共享工作台组件，但旧运营订单样板和中央员工独立订单入口分别沿用各自权限体系。保留未选方向以便比较。验收目标覆盖用户指定的三个桌面宽度，并补查已有窄屏。后续根据评审选择页面族并逐页验收。[统一设计系统](../../../DESIGN_SYSTEM.md)

试点评审建议是经营总览采用数据驱动、订单作业采用高密度运营、商家和商品配置页继承现代简约的空间和操作层级。三个方向共享主题与控件，最终推广以用户评审为准。
