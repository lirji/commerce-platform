# B端品质迭代实施证据

基线 f355c28280df2afb555c81aca4bcd7cc99f8d6d0；任务分支 refactor/b-console-design-craft；用户授权正常提交、合并、推送 main，无生产部署。正式接口复用 [CONTRACTS](../../design/b-console-experience/CONTRACTS.md)，本轮无后端、数据库、依赖升级。产品源码与测试指纹见 SOURCE_FINGERPRINT.json。

| 切片 | 状态 | 实施与可观察结果 |
|---|---|---|
| C01 | DONE | 单一 React/Ant 品牌壳层、墨色导航、统一页面层级，Cmd/Ctrl+K 搜索；弹窗打开时保持表单焦点；必填字段保留干净名称和 aria-required |
| C02 | DONE | Dashboard/DailyTrend 使用 API 日金额，正负同轴、零值不造柱；键盘、触摸日期探索；指标和展开状态在 URL 中刷新恢复 |
| C03 | DONE | SKU 右侧操作固定，修订详情先呈现对象和版本事实；中央按业务页 lazy 加载，组织信息只显示一次，目录不重复页头 |
| C04 | DONE | 管理/中央/平台/门店页面族、详情、表单和错误状态的实际截图复查；跨浏览器与业务回归；包体实测，发现与修正记录见 REVIEW |
| C05 | DONE | Journey.entry/nodes 的只读关系图，显示真实 next/yesNext/noNext 分支和汇合；当前节点事实并排；配置不冒充执行记录；1120/720/390 工作区和返回焦点 |

C01/C03 共用主题、中央壳层与嵌入目录，作为一个逻辑交付单元；C02 和 C05 各自提交。现有会话、401/503恢复、权限与未知写入的原键重试契约保持，通过浏览器及真实HTTP复验。

生产前端没有新增写死业务 Mock。边界浏览器测试中的 transport fixtures 用于负值、错误和复杂分支；另用已持久化的隔离测试数据及真实 API 复核总览、旅程、商品与业务动作。私有访问文件留在忽略的 .local，未提交。
