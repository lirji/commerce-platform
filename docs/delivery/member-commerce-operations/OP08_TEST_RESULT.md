# OP08 TEST_RESULT

Local gate：PASS。远程 CI 在 DELIVERY_STATUS / CI_RESULT 中独立记录，不能用本地结果代替。

## 验证对象

基线 fb7adf6，加 OP01–OP08 本轮实现。实际代码指纹、同源 jar SHA-256 和逐测试类计数见 [verification.json](verification.json)。最后完整构建之后产品代码未再改；后续仅修正浏览器定位/新增浏览器场景、文档和证据。浏览器运行的是最终构建的 jar。

## 已完成检查

|检查|结果|证据|
|---|---|---|
|scripts/build.sh：前端安装/类型与生产构建、Maven clean verify、UI入jar|PASS，165 测试，零失败/跳过|verification.json；本地 .local/op08-final-build.log|
|真实 MySQL 语义及模块边界|PASS，MySQL 8.4.11，V1–V22|各切片 TEST_RESULT，schema-verification.txt|
|新增表及全部字段注释|PASS，空注释0|schema-verification.txt|
|npm audit --audit-level=high|PASS，0漏洞|.local/op08-audit.log|
|完整 Chromium 浏览器|PASS，10/10，无跳过/重试通过|verification.json、evidence/*.png|
|seed-operations.py 同租户重复执行|PASS，回放相同命令，无业务重复/覆盖|本地终端记录、命令幂等持久机制|
|健康/静态UI/匿名API拒绝|PASS，UP / UI asset可取 / 匿名401|deploy/smoke.py，8603|
|迁移不改历史、差异空白检查|PASS|V1–V15与基线无差异；git diff --check|
|合并前集中代码审查与整改|PASS，本轮无未解决阻塞发现|REVIEW.md|

165 测试构成：kernel 3、marketing 27、order 45、commerce-app 88、architecture 2。88 个应用测试包含原70个回归和本轮会员/权限/商品/成长/人群/促销/旅程/分析集成场景。

## 浏览器验收覆盖

1. 原会员下单、沙箱收款、履约、售后退款与权益冲正。
2. 原可视规则、低代码预览审批发布及窄屏。
3. 原无效凭据和退出会话。
4. 原旅程可视编排、手工入组和站内触达。
5. 会员资料、冻结/恢复/注销终态及历史。
6. 门店运营创建 SPU/规格、调价上架、修订历史及在已登录会话撤权。
7. 人工成长账本/升级、标签撤销恢复、等级规则展示。
8. 人群复制版本/刷新完整结果，阶梯优惠只读预览、复制新活动草稿。
9. 等级旅程通知和会员端成长、成交退款、费用面板及历史补齐。
10. 页面配置注册触发/频控、审批发布、真实注册事件及实例完成。

首轮7/9通过，两个失败为测试使用英文“Close”，实际界面按钮为“关闭”；按可访问中文名修正后全部通过，未放宽断言。成长、促销预览截图已人工视觉检查，内容可读、主要操作可达；截图为虚构演示数据，无凭据。

## 环境与限制

本地运行 8603 / commerce_test_20260923。原 8602 容器与原工作区用户修改不动。为保护隔离库内原故障注入租户，本机关闭全局 worker，用两套浏览器夹具身份执行有界事件/旅程 pump；CI 则使用临时独占数据库的自动 worker。

本轮未做生产部署、真实渠道、容量/压测或灾备恢复验证。活动承担金额不等于利润、旅程执行不等于成交归因。新增包体仍有原有大 bundle 提示，不影响构建，未为消除提示擅自重构全前端。
