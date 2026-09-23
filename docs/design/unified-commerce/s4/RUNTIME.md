# 本地运行

单业务JVM：127.0.0.1:8600，Spring Boot4.1.1，Java21；数据库复用dev-infra-mysql84-1（MySQL8.4.11，127.0.0.1:43306），隔离commerce_local及commerce_test_20260923。專用commerce_app仅获两个库权限，本地账号兼任Flyway迁移；生产应拆分迁移与应用权限。

首次：`python3 scripts/provision-local.py`创建新数据库/账号，有资源冲突会拒绝，不覆盖数据。凭据仅保存在.local/runtime.env（0600）。没有提交凭据，没有修改其他库。

验证：`bash scripts/verify.sh -o`。启动：`bash scripts/run-local.sh`（先构建Jar）。种子：`python3 scripts/seed-local.py`，重复执行同命令回放，访问令牌在.local/demo-access.json（0600）；只含本地演示权限，不是外部身份服务。健康：GET /actuator/health。停止本应用使用前台Ctrl-C，不关闭共享Docker。

连接池8、连接等待3s、事务/SQL10s、锁等待5s、请求体64KiB、HTTP线程64/连接256/等待队列64。没有宣称容量达标。应用日志只输出错误类型与traceId；诊断需按关联ID检查审计及受控日志，不打印身份令牌和数据库密码。

当前未部署到生产，未连接真实支付/发券/SSO/WMS。后续集成按用户要求后置。
