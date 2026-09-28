# 最新前端的本地 Docker 部署

结果：PASS。目标是现有 `commerce-platform/app`，Docker context `desktop-linux`，访问地址 [8602](http://127.0.0.1:8602)。用户在前端交付后明确授权重新部署。本次只替换这个 app，沿用 `dev-infra` 和 `commerce_local`。

- 部署源码：`919081be0deef6024f39ba2ea4c232d2ed56fdab`，[该 main 提交 CI 成功](https://github.com/lirji/commerce-platform/actions/runs/36379346310)。后续提交只记录部署事实，不改变应用构建输入。
- 镜像：`commerce-platform:rev-919081b`，ID `sha256:fa6b89cfea91db24b7b13dcc45db169c6be37e3243f56d7bb7abf5773aaaacd7`。源码 revision 和 JAR 摘要写入镜像标签；私密 Compose 配置已持久保存该版本 tag。
- JAR SHA256：`61a0ae6563cfdabb6a39837d35f5c3a6e727b9b52447dd5918f60c98041c3fe3`，宿主产物和运行容器内文件一致。
- `scripts/build.sh` 成功：类型检查、Vite 和真实数据库 Maven 验证。汇总 375 项、0 失败/错误，5 项按既有配置跳过；不将跳过项记为通过。JAR 内全部 UI 文件与本次 `frontend/dist` 一致。
- Compose 静态校验及 `up -d --no-build --wait ... app` 成功；新容器于 `2026-09-28T05:03:09.551293002Z` 启动，状态 running/healthy。
- 已经通过 HTTP 逐文件核对全部 26 个静态资源，摘要与本次构建一致。健康 UP，匿名身份 401，既有管理员/会员身份均 200，会员访问管理总览 403。
- 数据库 V40 顺序追加 V41–V45，45 条迁移全部 success。既有迁移文件校验和未改，升级前项目库快照保存在本机私密目录；没有清库或执行共享中间件 down。DB 目标/账号/密码、地址密钥及 sandbox/worker 配置比较一致。
- Docker 地址上两项浏览器回归 2/2 成功，验证窄屏表格、字段错误与焦点、商品图片/缺图、购买区域和真实报价。实际查看四张 Docker 图片：[经营台](../../evidence/frontend-visual-refresh/docker-after/dashboard-desktop.png)、[商品列表](../../evidence/frontend-visual-refresh/docker-after/catalog-desktop.png)、[桌面商城](../../evidence/frontend-visual-refresh/docker-after/shop-desktop.png)、[手机商城](../../evidence/frontend-visual-refresh/docker-after/shop-mobile.png)。私密取图共生成 21 张，本记录仅宣称实际查看上述四张。

旧镜像保留为 `commerce-platform:before-919081b`。未发生回滚；追加 schema 后，旧 V40 二进制对新数据的完整回退未认证，故障应保留扩展 schema/业务事实并使用兼容修复版本，不执行数据库回退。券活动与扩展 Trace 保持默认关闭，不额外启用能力。升级前快照没有执行恢复演练，不声明灾备指标。

机器记录见 [DEPLOYMENT_RESULT.json](DEPLOYMENT_RESULT.json)，四张图片的当前来源、版本与复核见 [capture.json](../../evidence/frontend-visual-refresh/docker-after/capture.json)。私密配置、数据库快照及访问凭据不进入 Git。
