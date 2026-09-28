# 低代码发布回归时序修复

最终文档提交 `823d200` 的 [main CI](https://github.com/lirji/commerce-platform/actions/runs/36377797418) 有 25 项浏览器检查通过，1 项既有低代码页面用例失败：点击“发布”后，已发布内容标题未出现。同提交的 [feature CI](https://github.com/lirji/commerce-platform/actions/runs/36377767994) 成功，说明需要核实执行时序，不能忽略 main 的失败。

源码中的“发布”命令异步提交，表格刷新后才显示 `PUBLISHED`；“打开 / 版本”在请求期间仍可点击。旧测试点击后立即打开，读取请求可能先于发布提交完成。私密复现只将真实发布 POST 延迟 1500 ms 后继续转发，没有提供伪造响应或业务数据：旧用例实际得到 `GET render → 404`，之后才收到 `POST publish → 200`，原标题断言按预期失败。

[commerce.spec.ts](../../../frontend/tests/commerce.spec.ts) 增加该页面行的“已发布”状态断言，然后在该行打开页面。原标题、真实数据和窄屏断言全部保留，没有增加重试、扩大超时或降低断言。同一延迟条件下，修复后的真实顺序为 `POST publish → 200`，再 `GET render → 200`，用例通过。两次请求顺序和结果见 [RACE_VERIFICATION](RACE_VERIFICATION.json)。延迟与网络记录只存在于本机私密诊断副本，正式测试仅增加状态前置条件。

8 个页面产品文件的摘要仍为 `3a72634e2bfdbf5b15cd07377373dc6bb281b4013ff2d82e506b13329e589876`；21 张已查看图片继续对应当前页面。修复后本地完整 26 项回归、构建和 Prettier 检查再次通过，结果与测试源码摘要见 [REGRESSION_RESULT](REGRESSION_RESULT.json)。公共扫描对本批测试/文档返回 `NOT_APPLICABLE`、无 finding；原产品质量门禁继续对应不变的源码，前端单元套件缺失的限制保持记录。精确提交 CI 复核与 Git 发布继续按实际结果同步。
