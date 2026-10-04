# 前端任务与恢复优化

本轮用户授权全站前端优化，沿用已确认的32px按钮、居中弹层、React/Ant Design及真实查询契约。不增加后端、依赖、导航架构或运行部署。

使用Claude当前个人入口 `/Users/liruijun/.claude/skills/frontend-design/SKILL.md`（指向共享核心，修改时间2026-10-02 23:30本机时间），SHA256 `c5b3d5c93895e89bdae8a5ec7103f8b10113aa327788683cf591ea057fbaab0b`。该入口比marketplace副本新，附属interaction-design、visual-design、workbench及项目运行验证规则一并按本轮范围应用。执行者为Codex直接读取技能，不声称由Claude原生客户端或独立验证代理执行。

|范围|已有字段/数据与资格|本轮完成标志|
|---|---|---|
|共享ListFilters及全部消费者|关键词范围按已有spec；状态/启用/创建时间/limit按Owner契约；URL保存已应用条件|输入未提交时明确提示；已应用条件可逐项清除；清除/重置从首段重新请求，保留其余实际条件；相同URL也正确清除草稿|
|ErrorNotice读取消费者|useResource提供真实GET与refresh；业务命令保持原错误/幂等/未知结果规则|读取故障就地重试、保留上下文；401/403不提供无效重试；命令错误不接读取重试|
|ListPanel宽表及消费者|表格字段、操作、游标均沿用各页面|实际横向溢出时可聚焦滚动；左右方向键只处理容器，不截获行操作；长字段和焦点可读，短表不增加Tab停靠点|
|运营/会员/中央壳层|App和CentralShell保留各自真实会话、权限与导航模型|跳过导航进入main，URL、筛选和游标不变；移动导航与弹层焦点/关闭保持有效|
|低代码运营目录|既有`/admin/ops-pages`的关键词、状态、limit与游标|使用共享筛选与ListPanel，刷新恢复真实查询；800px宽表内部滚动，名称及操作可读|
|真实数据下的手机页面|动态人群按钮组、营销日期表单、会员成长行为卡片|320px下可换行，卡片不撑宽页面，完整字段在表内横向滚动|

F1完成上述实现及定向行为验证；F2按页面族检查共享消费者、1440/390/320px、真实只读API界面、截图实际查看、构建/格式，再进行Git交付。测试边界只证明正式DTO对应的浏览器行为，真实读写权限与PKCE不由夹具冒充；本轮不改业务权限。无可信总数等已知接口边界沿用原方案，不展示假分页或新控件。本轮未运行200%缩放或完整屏幕阅读器验收，实际验证及失败闭环见[实施与验证证据](IMPLEMENTATION_EVIDENCE.md)。

视觉依据继续为 [统一前端架构](../../design/unified-commerce/FRONTEND_ARCHITECTURE.md)、[已有可用性方案](../../design/frontend-usability.md)。本轮检查使用Playwright现有[可等待断言](https://playwright.dev/docs/test-assertions)与W3C[焦点可见说明](https://www.w3.org/WAI/WCAG22/Understanding/focus-visible.html)，不宣称全面无障碍合规或用户已认可新截图。

## F3 后续明确授权的本机部署（DONE）

源码交付后用户明确要求部署，并在Docker无响应时允许重启后继续。目标限定既有本机8602、已通过精确CI的2851fc1；保留数据库、私密配置和回退镜像。完整验证、实际镜像切换与部署后验收已经完成，见[部署结果](DEPLOYMENT_RESULT.md)。这项后续授权不改变前端源码阶段原范围，也不授权生产部署。
