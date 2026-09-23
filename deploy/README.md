# 本地运行与恢复

本方案交付本地可运行的统一应用，不执行生产部署。应用唯一持久化依赖是 MySQL；复用已存在的 `dev-infra` 外部网络和 `dev-infra-mysql84-1`（网络别名 `mysql84`）。不要执行共享基础设施的 down、清库或重建。

## 连接与私密配置

| 用途 | 地址/标识 | 权限边界 |
|---|---|---|
| UI/API | http://127.0.0.1:8602 | 仅本机回环；容器内部 8600 |
| 本地数据库 | 127.0.0.1:43306 / commerce_local | commerce_app 仅项目 schema |
| 测试数据库 | 127.0.0.1:43306 / commerce_test_20260923 | 测试启动检查 schema 名称 |
| 容器数据库 | mysql84:3306 / commerce_local | 外部网络 dev-infra |
| 认证演示 | .local/demo-access.json | 本机 0600，不进入 Git |
| 环境配置 | .local/runtime.env、.local/compose.env | 本机 0600，不进入 Git |

现有机器已经配置上述资源。新机器先由数据库管理方提供这两个隔离 schema 和仅限项目范围的账号；不要拿共享 root 账号运行应用。参照 `deploy/env.example`，在 `.local/runtime.env` 写 `export KEY='value'`：COMMERCE_DB_URL、COMMERCE_TEST_DB_URL（均含 UTC JDBC 参数）、COMMERCE_DB_USER、COMMERCE_DB_PASSWORD、COMMERCE_ADDRESS_KEY（32 字节随机值的 Base64）、COMMERCE_SANDBOX_ENABLED、COMMERCE_WORKERS_ENABLED。本地演示将最后两项设为 true。设置文件权限 0600。不要更换已有地址加密密钥，否则旧密文无法恢复。

`configure-local.py` 只在 compose.env 不存在时从现有私密配置生成容器配置；既有配置保留。外部网络/主机不同需维护自己的私密 compose.env，模板不包含真实口令。CI 使用专用临时 MySQL 和 `ci-configure.py`，该脚本拒绝本地/共享实例，不用于开发数据库初始化。

## 构建、启动及数据

1. `./scripts/build.sh`：npm ci、类型检查/Vite、Maven clean verify 和 UI 入 jar 检查。需要两个数据库环境变量及测试库可达；不跳过失败验证打包。
2. `./deploy/up.sh`：校验 Compose 并构建启动 app，等待健康。无 DB 容器、无数据卷、无自动数据库清理。
3. `COMMERCE_BASE_URL=http://127.0.0.1:8602 python3 scripts/seed-local.py`：演示会员/商家/店铺/3 个 SKU、库存、活动、人群、规则、券、权益、旅程、运营页，以及一笔完成订单、一笔全额退款订单。令牌摘要写认证表，其余业务全部经 API。
4. `python3 deploy/smoke.py`：验证健康、UI 静态资源及匿名 API 拒绝；业务正确性另看数据库/浏览器测试。

种子命令按 UTC 日期固定输入和幂等键。同一天可重复执行；跨日新建人群/活动版本和两笔演示订单，以保持人群新鲜度，不是无限期无新增的全局 seed。种子历史报价回放可能已过期，购买时由前端重新请求报价。种子不删除已有数据；大量演示日积累后应另行制定归档政策。

浏览器验收先执行 `COMMERCE_E2E_BASE_URL=http://127.0.0.1:8602 python3 scripts/prepare-e2e.py`，必要时在 frontend 执行 `npx playwright install chromium`，再按 README 执行 e2e。每次使用独立租户，测试 token 1 天有效；不清理其他租户。

## 运行、失败和回滚

- 容器使用固定基础镜像 digest、非 root 用户、只读根目录、64 MiB 临时目录、资源上限、健康检查和优雅停止。容器无状态，数据存项目 MySQL。
- 诊断：`docker compose --env-file .local/compose.env ps`、`logs --tail=100 app`。不要公开 `docker inspect` 完整环境或 `compose config` 展开的机密；校验使用 `config --quiet`。
- API 错误返回 traceId；事件/旅程列表展示状态、尝试数和待处理记录；隔离记录由管理员核查后按接口重试。Worker 开关关闭会停止自动推进，应用健康不能证明积压为零。
- `./deploy/down.sh` 仅停止本应用；之后 up 恢复。同源 UI 和业务数据应仍可查询。共享 MySQL 不受这些命令管理。
- 发布前保存已验证 jar/image 的不可变引用；先验证迁移与事件兼容，再切应用版本。Flyway 已应用的 V1–V34 不允许改历史或执行 clean。V13 扩展了权益来源和可空订单关联，不能无验证回退到 S8 旧应用。S9+ 也需要检查具体 schema/事件兼容，不承诺任意旧镜像能滚回。
- 已提交的支付/退款、券使用、发权益和通知是业务事实；镜像回退不撤销这些效果，应走售后/权益补偿流程。地址密钥与数据库备份必须匹配保存；本次未执行备份恢复演练，不宣称达到 RTO/RPO。

## 外部适配后置清单

| 目标 | 当前交付 | 真实联调前需要 |
|---|---|---|
| IdP/权限 | 数据库摘要 Bearer、服务端角色/租户检查 | 主体映射、令牌校验/撤销、真实租户与权限合同 |
| 支付/退款 | 持久沙箱和渠道端口、未知查单/对账 | 渠道沙箱配置、验签、商户、回调重放与金额币种核对 |
| 权益渠道 | 内部额度账本、预占/发放/消费/冲正/补偿 | 外部渠道幂等、查询、未知结果、补偿合同 |
| WMS | 本地履约状态和物流单号 | 发货请求、回执验真、库存所有权与退货入库合同 |

这些联调依用户要求留到整体建设结束后；本次没有接触真实渠道或生产环境。

## 会员经营增量

新增 V16–V22，不新增环境变量或公共中间件。OPERATOR 商品授权、CLOSED 会员和会员事件旅程不兼容任意旧镜像回退。新版可在宿主机另用 COMMERCE_PORT=8603 启动 jar，显式将 COMMERCE_DB_URL 指向 COMMERCE_TEST_DB_URL；保留私密配置和密钥；该阶段的隔离验收不代表容器更新，最新部署以本轮部署记录为准。本轮 seed、报表回填、权限与玩法说明见 [经营手册](../docs/delivery/member-commerce-operations/OPERATIONS_GUIDE.md)。

新增浏览器脚本还需 `scripts/seed-operations.py --fresh`，schema 默认 commerce_local，测试库需显式 COMMERCE_E2E_SCHEMA=commerce_test_20260923。令牌仅在 `.local/operations-access.json`，版本有效期沿首次种子时间；脚本不会清数据或覆盖运营修改。


## 会员链路与深色工作台增量（LP01–LP11）

V23–V34追加会员周期/积分/行为、定向发券/旅程效果、商品资料/经营任务/渠道价。无新增公共组件或环境变量。完整玩法和失败恢复见 [运营手册](../docs/delivery/member-lifecycle-catalog-ui/OPERATIONS_GUIDE.md)，当前交付/运行状态见 [进度](../docs/delivery/member-lifecycle-catalog-ui/STATUS.md) 与 [部署记录](../docs/delivery/member-lifecycle-catalog-ui/DEPLOYMENT_RESULT.md)。

`.local/member-suite-access.json` 保存专项演示身份。运行 `COMMERCE_E2E_BASE_URL=http://127.0.0.1:8602 python3 scripts/seed-member-suite.py --fresh` 通过API创建新隔离演示租户，不清数据；随后不带 `--fresh` 可验证重放。生日、券、人群与兑换有效窗固定于首次创建，过期后可新建夹具，不覆盖运营数据。认证MINI_APP渠道需要受控运维设置platform_credential.sales_channel，没有客户端自选接口。

本轮启用新功能前需所有应用与worker升级；特别是新旅程节点、积分订单行与渠道报价不能交给旧版本处理。升级后不得直接回退到仅V22语义的镜像。失败时先限制新增入口并停止worker，再核对数据库与既有订单快照，使用兼容修复版本恢复；保持数据和地址密钥，不能将镜像回退当作业务补偿。
