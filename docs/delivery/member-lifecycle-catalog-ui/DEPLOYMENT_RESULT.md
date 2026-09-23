# 本地Docker部署记录

结果：PASS。部署时间：2026-09-23T18:22:58.616185+00:00

- 目标：现有commerce-platform/app，http://127.0.0.1:8602，同源UI/API；复用dev-infra MySQL、commerce_local。无生产部署、无共享中间件变更。
- 实现commit：`701b4f115777e5fbe8c3d18712cca2154d932fab`。部署前该ref完整远程CI 35901296266 PASS。
- 镜像：`commerce-platform:lp11-701b4f1`，ID `sha256:66b523270dfabe02f63cc426b250866fbcf76f62253ab50b2ac209f9ec370fe2`。
- jar：SHA256 `0b8b7aadb862b1783718bc34c92f99fdb52cb412b41bc81994017c5020751bad`，容器内再次计算一致；运行镜像ID与构建ID一致。
- Compose：静态校验PASS，`up -d --no-build --wait ... app` PASS，running/healthy。只替换本项目app；原数据库目标/用户名/密码/地址密钥比较一致，未打印值。
- 迁移：commerce_local 从V22追加至V34，最后记录success=1；未修改历史迁移、清库或覆盖用户数据。
- Smoke：health UP、同源UI动态资产200、匿名身份401、会员访问管理总览403。周期等级GOLD、积分钱包、兑换目录、商品目录、经营任务/渠道价接口均PASS。
- 业务核对：真实API种子两笔已付订单，实收70.00、退款35.00、净收35.00；新建/幂等重放PASS。
- 容器浏览器：2/2 PASS，包含自动worker开启时经营计划完成及回执/渠道价历史、真实总览/导航/手机390px/错误态。`.local/lp11-docker-browser.log`；截图在`.local/member-suite-docker-evidence/`。
- 私密身份：本工作区`.local/member-suite-access.json`现指向8602。8604旧夹具已保留为`.local/member-suite-access-8604.json`。不将令牌写入文档/聊天/Git。

此前镜像`sha256:dfe19f58c09ba42014df8f7da20a5abe6edfb761b7340fd110f88e6a6952ab36`仍保留，但新功能已启用后不能直接回退到仅V22语义的代码。故障先限制新入口/worker，保留数据和密钥，用兼容版本修复；已发券/积分/调价等效果按业务补偿处理。此次无需回滚。

后续仅文档/测试修订可能使Git最新commit比实现commit更新；实际部署业务产物以本记录的不可变jar与镜像摘要为准。
