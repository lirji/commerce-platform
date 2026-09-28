# P3 商城范围试点运行与契约

依赖auth固定提交见scripts/auth-sdk-source.ref，沿用P2双身份服务协议及本地身份桥。启用需要commerce.iam.store-read.enabled=true与commerce.iam.scope.enabled=true；缺省关闭。私密配置沿用commerce.iam.store-read.configuration（0600），不把服务凭据或用户Token存入导出表。auth服务同时启用governance/access/scope，scope.check.callers限定服务，scope.owner.commerce=store,product。目录与策略严格分区分别收敛后可读。

## 路由与权限权威

| 路径 | 本阶段权威 |
|---|---|
| /v1/operations/scoped/store、product及其详情/导出 | 中央实时ScopePlan + 本地有效OPERATOR身份桥 + 资源Owner SQL |
| GET /v1/operations/stores（同时启用scope时） | 同一中央范围SQL，保留原列表响应；after只接受随机服务端游标 |
| 既有 /v1/operations/products、其他旧业务路径 | 保持既有认证/ACL；中央Token不能借旧管理员入口调用 |

菜单是访问提示；每个真实入口仍检查。订单、退款及商品写操作迁移不属于P3；P3不代表全部业务路由已中央化。

## 可观察接口

GET /v1/operations/scoped/{type}?limit=50&search=&cursor= 返回rows、total、stores、nextCursor；type仅store/product，limit 1—100、search最多100字符。详情GET /resources/{id}的归属和版本由Owner读库，不接受前端Facts。Authorization为用户Bearer，X-Tenant-Id为中央租户；缺失身份401，无权403，版本冲突409，授权状态未收敛/依赖故障503。

范围同Grant条件AND、不同完整Grant路径OR；SQL参数绑定并保留本地tenant_id谓词。store使用store_id；product使用store_id与product_id。支持TENANT_ALL、SPECIFIED_STORES、SPECIFIED_RESOURCES；未绑定的SELF、部门、供应商范围拒绝。列表、搜索、count与stores统计共享相同过滤SQL。详情二次比较完整资源行与版本。

POST /exports?search=提交（202）；POST /exports/{id}/start?version=启动；POST /exports/{id}/advance?version=推进；三个命令都要求Idempotency-Key。GET /exports/{id}查看，GET /exports/{id}/download私密下载，无公开URL，响应no-store。SUBMITTED→RUNNING→COMPLETED采用数据库锁、条件版本与同事务命令回执。每批50行、最多1000行；调用者逐批推进，不创建未批准后台调度器。

游标有效5分钟，绑定主体、成员代际、查询、资源类型、完整Grant集合与策略/目录版本。导出检查点有效15分钟，同绑定且校验申请人；提交、实际开始、每批及下载都重新判权。下载每50行比对当前SQL范围、资源完整快照和版本；新增Grant也不能让旧检查点自动采用新范围。进程重启后用同任务和当前版本恢复；同命令重放不重复写行。

同租户最多10个未到期任务，每主体最多2个（包含已完成但未到期），配额锁保障并发。SQL查询/图候选/响应/导出均有上限；count/search仍可能按租户扫描，未声称已达到生产大数据性能。快照不是业务一致性报表：变更中的资源会使下载拒绝并要求重新生成。

## 迁移、保留与回退

V47新增游标、配额、任务及导出行表；已执行迁移不可改写。任务过期后不能访问，但不自动删除物理行。隔离验收库保留以便复验；生产保留期限、定时清理与容量预算仍应在生产接入前确认，不能将15分钟访问期限当成物理删除承诺。回退应用前先停试点流量，不能把关闭scope开关当成严格授权的降级许可；数据库扩展可保留，旧代码不读取新表。

## 复验

先安装固定SDK scripts/install-auth-sdk.sh，使用既有私密.local/runtime.env执行mvn verify。真实MySQL CentralScopeMySqlTest六项覆盖失败回滚、并发、幂等和隔离；其SDK为协议替身，仅证明本地事务语义。

跨进程验收：python3 scripts/iam-scope-smoke.py --auth-root ../auth-platform。需要已构建auth admin/server及commerce Jar、auth自有隔离IdP18090、P2/P3图18543/18544、dev_infra PG/MySQL和私密fixtures。工具创建专用商城库和权限用户、租户/Grant/身份桥及SQL种子；发布隔离commerce manifest v2（含product.read），启动自有18111/18113/18112/18603进程，结束只停自己进程。保留库和0600证据，不清理共享数据；原8602、8000和8543服务不动。P2旧HTTP脚本使用manifest v1，不能在已发布v2的同一历史中降级重跑；CI使用独立新库。

真实验收证据见evidence/http-result.json、http-performance.json。本次小样本仅本地基线；生产SSO、共享Casdoor升级和生产部署没有在本阶段执行。
