# P6 商城中央经营与迁移验证

完整commerce.catalog.operate沿用原商品、SKU、渠道价、上下架、类目/模板、图文/条码、持久任务业务用例。新增默认关闭的中央经营认证链、无Token执行引用、数据库单元路由与旧写冻结；原8602商城未切换。

新增CentralCatalogMySqlTest五项通过：完整经营效果、撤权/过期/重放拒绝、旧写冻结与安全停止、HTTP旧Bearer不绕过、切换等待已经取得授权的旧事务。最终完整reactor验证393项：388通过、5个可选性能测试跳过，失败0。两仓hygiene为COMPLETE_WITH_LIMITATIONS（无统一formatter/静态分析未配置）。SDK来源钉auth fc82340761b30936f3efde2e28df44f1cb6c3dde；Git/CI见auth主报告。

真实Casdoor/PG/SpiceDB/独立MySQL/实际worker的31项验收见[统一证据](https://github.com/lirji/auth-platform/blob/main/docs/implementation/oa-auth/phase-6/P6_REHEARSAL_RESULT.json)。真实来源1条保留到期拒绝，正向1条是独立有限时夹具，不能冒充真实OA身份迁移。

V48创建触发器需独立DDL Owner：使用SPRING_FLYWAY_USER/PASSWORD指定迁移连接，业务连接仍普通账号。演练先迁移后移除Owner凭据重启验证。没有修改共享MySQL全局变量，测试失败迁移经过明确核对后恢复，没有删除业务数据。

中央权威下ADMIN、直接Java调用和历史任务也走守卫；授权表INSERT/UPDATE/DELETE冻结由数据库约束。中央后只能STOPPED或CENTRAL，不能退回忽略新撤权的旧权威。已提交业务效果需要业务补偿。目录epoch变化使旧任务引用失效，需要用户重新提交任务。

前端P5入口能力保持独立；本片验证既有经营API和后台，真实全部客户端SSO迁移属于生产候选门禁。生产HOLD/P7未执行，详见[候选报告](https://github.com/lirji/auth-platform/blob/main/docs/implementation/oa-auth/phase-6/P6-07_CANDIDATE_REPORT.md)。
