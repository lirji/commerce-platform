# P5-07 验收

PASS。统一入口、商品试点及真实登录运行验收汇总由auth仓库[P5-07_TEST_RESULT](https://github.com/lirji/auth-platform/blob/main/docs/implementation/oa-auth/phase-5/P5-07_TEST_RESULT.md)维护，截图和源码摘要在对应evidence/p5-07。

- 本次商城全reactor `mvn test`：388项，383通过、5未开启性能profile跳过，零失败；不把跳过计入PASS。原库存逐项重试测试受共享库扫描耗时影响，先断言生产退避时间，再固定测试窗口300秒，生产重试不改；完整回归重跑通过。
- 真实内部门店商品试点最终http-3a8ef5cd25e6：25 HTTP、7浏览器检查通过，未保存关闭确认及继续编辑、真实写版本推进、越范围/旧版本拒绝、撤权后读保留写拒绝。1440/390及确认弹层截图实际查看。
- 隔离IdP首次真实密码登录、门户→商城→OA独立客户端SSO、PKCE换码、错误state清理、跨受众/Cookie-only401；真实auth停机503/恢复通过。浏览器脚本没有注入会话Token或Mock接口。
- JAR同源打包运行5检查通过：SPA深链/真实OIDC回调/资源API，手机详情刷新，OA非法Origin拒绝，浏览器时钟过期后清数据并登录，关闭双中央试点开关后旧有效Token401。
- 前端新增401重新登录、失败回调去除code/state、未保存关闭保护、StrictMode撤销effect不发送废弃请求；最终构建及Prettier通过，Code Hygiene无阻断，仅既有全仓formatter不可用限制。

生产共享IdP升级HOLD不变，本轮未生产部署、未进入P6。商品update/read/export分别授权；旧导出不能凭read续跑。运行变量见frontend/.env.example，完整部署/回退说明见auth P5_RUNTIME。
