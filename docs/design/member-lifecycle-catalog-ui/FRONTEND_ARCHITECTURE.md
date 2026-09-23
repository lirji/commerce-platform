# 深色经营工作台

用户明确选择深色数据看板。保留 React + TypeScript + AntD 一套体系、现有 fetch/Hash 路由与内存身份；无需 Material/Tailwind/新状态库。会员、商品、营销、交易、分析分组，店铺和筛选保存在 URL，上下文切换清理陈旧数据。

| 角色 | token |
|---|---|
| canvas / surface / raised | #0B1020 / #121B2E / #19243A |
| ink / muted / line | #EEF2FF / #A8B6CF / #34425B |
| accent / hover / ink | #315CDA / #264CBF / #FFFFFF |
| ok / warn / err / pending / idle | #047857 / #A34D08 / #B91C1C / #5742BA / #43516B |
| status ink / focus | #FFFFFF / #9CB7FF |
| radius / type / spacing | 10px；12/13/16/20px；4/8/16/24px |
| control / touch | 36px / 44px；tabular-nums；系统中文字体；无 webfont |

Ant ConfigProvider darkAlgorithm 与 CSS 变量同源。登录与工作台共用标识和主题。桌面侧栏+上下文栏；首页真实指标与待办队列，标注范围和更新时间，不用假趋势。列表页：页头+一行筛选+表格；单据页：事实+动作+关联记录；编辑抽屉不叠加模态。数字右对齐，长 ID 省略可复制。表体横向滚动，窄屏导航可收起。
loading 保留条件/禁提交；空态解释范围；错误不能显示为无数据；401/403 明确拒绝；202 持续显示任务状态；409 刷新后再确认且不自动换幂等键。金额/单位/UTC 时间含义可见。焦点可见、标签和错误关联，状态含文字；减少动效。截图验收桌面1440和窄屏390，不以仅构建成功代替视觉验证。
