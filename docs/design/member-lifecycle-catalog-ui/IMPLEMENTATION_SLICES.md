# 实施切片

全部串行，避免共享迁移/契约写冲突。Owner 对应现有后端/前端实施技能，无子 Agent。每片先补细契约，再实现 API→页面→真实数据库/浏览器验证；新增迁移按实际顺序编号，除 LP01 不预占。Runtime 均复用当前 MySQL/应用。

|ID|可观察结果|Needs|路径|验收|状态|
|---|---|---|---|---|---|
|LP01|周期等级策略、考核与会员可查|—|member/app/V23|边界、退款、并发、旧租户兼容|DONE|
|LP02|等级权益绑定、幂等发放与会员页面|LP01|benefit/app/frontend|周期续发、重复/降级、真实钱包|DONE|
|LP03|积分策略、获取、到期和账本页面|LP01|member/app/frontend|幂等、乱序退款、过期债务|DONE|
|LP04|积分订单抵扣与退款返还|LP03|trade/order/aftersales/frontend|冻结/取消/UNKNOWN/零元/部分退款|DONE|
|LP05|积分兑换券和权益|LP03|benefit/member/frontend|原子扣分发放、库存不足回滚|DONE|
|LP06|会员行为、人群和详情|LP01|member/campaign/frontend|身份隔离、来源去重、分页分群|DONE|
|LP07|定向发券与相对有效期|LP06|benefit/automation/frontend|任务恢复、频控、配额、撤销|TODO|
|LP08|生命周期旅程与效果比较|LP06,LP07|automation/app/frontend|生日/沉睡/复购/加购、等待、退款成本|TODO|
|LP09|商品类目、规格模板、条码图片、检索|—|catalog/app/frontend|权限、唯一、筛选、非法模板|TODO|
|LP10|批量及定时经营、渠道价格|LP09|catalog/trade/frontend|版本冲突、恢复、渠道报价|TODO|
|LP11|深色经营工作台和整体验收|LP02–LP10|frontend/tests/scripts/docs|真实数据、空错状态、桌面窄屏、CI|TODO|

当前下一片 LP07（定向发券与相对有效期）。LP07–LP11 尚须细化对应契约，非环境阻塞。前端深色基础随 LP02 页面同步引入，LP11 最终收口。每片必要窄测试通过后继续，不反复请求继续。
