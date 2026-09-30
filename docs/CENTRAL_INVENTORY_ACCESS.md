# 中央员工库存权限

CE-03-I仅接入 `GET /v1/admin/inventory?storeId=...` 与 `POST /v1/admin/inventory/receipts`。请求形状保持，分别检查commerce.inventory.read/receive。员工为OPERATOR，不获得ADMIN；库存门店由Owner核对，SKU必须真实属于目标门店。CATALOG不隐含库存权限，库存两能力也不相互包含。

开启中央链需现有 `commerce.iam.store-read.enabled=true`、私有配置文件及新增 `commerce.iam.employee.enabled=true`。配置文件和SDK绑定沿现有中央入口；Token只在请求头，X-Tenant-Id仅选择已映射中央租户。V46绑定继续要求有效本地OPERATOR；真实人员绑定、能力角色与Grant仍须Owner明确审核，不自动迁移ADMIN。

V49 employee_authority_route以租户+INVENTORY能力族持久接管，LEGACY/SHADOW允许原用例规则；CENTRAL只接受中央执行引用，STOPPED拒绝。CENTRAL后不能删除或回退LEGACY，停配置不能重开旧ADMIN路径。CATALOG继续用其独立路由。当前没有公开迁移状态写API，生产迁移未执行。

执行引用按单能力签发，库存最多60秒；每次用例重新判权，不把TTL当权限缓存。网络检查在命令事务外；五秒内准入并在Owner锁后复查许可，事务沿用10秒上限。命令回执读取前锁定权威路由/门店事实；同键不同主体代际冲突，不泄露旧回执。V50保存中央principal/member/generation、执行引用、能力、门店和路由版本，与库存、命令及原审计同事务提交，不保存Token。

订单预占、确认、取消释放、售后回补属于既有业务履约，不由员工Grant决定。中央故障503、认证失效401、无权/旧入口403、版本/幂等409；不回退旧授权。数据库触发器DDL需要迁移Owner，应用进程继续用原受限账号，不修改共享MySQL的全局binlog设置。

交付验证：完整Java397项（392通过/5可选跳过）、前端构建和架构检查通过；新4项真实MySQL+HTTP检查，覆盖范围、读写分离、身份代际、回执、到期、路由并发及变更拒绝。另有auth真实PG+图2项执行引用IT。完整auth+商城跨进程51项通过，证据auth仓 `.local/governance/p6/rehearsal-e068a97301ff/result.json`，源商品经营31项继续通过；额外检查包含明确的库存独立Grant，不把CATALOG授予库存。

失败历史保留：初次共享测试库迁移权限1419已受控修复，空表/失败记录留证；初次完整回归一项订单公平性失败，独立7项及后续完整回归通过，未改旧断言；第一次联调新Grant尚未投影就绪，增加有界只读就绪探测后通过，入库/撤权检查不重试。详见auth仓commerce-readiness/CE03_INVENTORY.md。

中央库存新页面由CE-03-U接续，目前只交付后端。上线顺序为先支持库存引用的auth版本、再知晓分族路由的commerce版本，最后登记/授权/切换目标单元。旧版本不认识路由，不能在已接管单元上回退运行；应保留新版本并STOPPED。原商城8602未切换或重启。

## 员工库存页面（CE-03-U）

固定入口 `/operations/inventory?tenant_id=<中央组织UUID>&store_id=<门店ID>` 复用企业 SSO。页面不调用旧 `/v1/me`、会员或商品目录，不从库存能力推导门店目录。SKU由员工输入已知的当前门店已发布标识。旧管理控制台保持原客户端。

`GET /v1/operations/inventory/actions?storeId=...` 需要库存 read，并对同一主体、真实门店独立检查 receive，返回最小 `{receive:boolean}`。无写权限隐藏入库表单；依赖异常返回503而非false，动作提示不替代真实入库检查。新接口与既有库存接口共用分族CENTRAL路由及单Bearer/租户头校验。

页面读列表按SKU游标每页50条；403清除旧结果，401撤下业务界面，503显示不可用。入库结果未知时固定输入和幂等键，允许原样重试；后续拒绝也不证明旧命令未生效，因此继续保留未知命令。切换门店或退出前提醒未保存/未确认结果，浏览器刷新提醒；无离线持久化命令账本。只有成功回执才重置命令并刷新库存。

验证PASS：398项Java（393通过/5可选跳过）、TypeScript/Vite与Prettier、最终打包通过。auth仓rehearsal-ba03baa64c88真实隔离58项通过，包含库存9条浏览器细分及CATALOG9条回归；桌面表单、未知结果、390和故障截图已查看。hygiene无阻断，Java无formatter及既有大chunk告警保留。新授权可能短暂保守拒绝，工具就绪检查不等于生产延迟目标达标；一次新授权403的拒绝层未追溯，失败历史见auth仓CE03_INVENTORY.md。不代表真实人员迁移或生产可用。
