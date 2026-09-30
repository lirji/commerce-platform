# 权益定义与实例中央员工权限

沿 auth `CONTRACTS_COMMERCE_ENTITLEMENTS.md`。SDK 固定 `3a4cef39923e4d27b2f794a7d0fc06f8690a0d26`；V61 追加 ENTITLEMENT_DEFINITION、ENTITLEMENT 两个独立接管族与实际审计类型，不自动切换租户。四个 HIGH 能力均为完整租户范围：定义 read/create、实例 read/resolve，读写互不附赠。

定义 GET/POST `/v1/admin/entitlement-definitions` 按原门店/商家状态、数量、发行配额和时间校验。目录先取 tenant+benefitId 全局最新版本，再按门店过滤、benefitId 稳定分页，返回前复核范围。创建先锁接管路由/核对期限再读幂等回执；中央稳定身份进入原输入摘要，旧模式摘要保持。实际 benefitId 身份审计、定义及命令回执在同一事务。

实例 GET `/v1/admin/entitlements` 按实际租户和 grantId 分页，包含各状态。POST `/v1/admin/entitlements/{id}/resolve` 先读取真实 grantId/version 判权，再在事务内锁路由及实例、复核事实和期限，之后才能读取旧回执。版本不进入稳定幂等摘要。沿原 COMPENSATION_REQUIRED → COMPENSATED 迁移、CAS 及 RECOVERED/WRITTEN_OFF 补偿账本；实际 grantId 身份审计同事务，审计失败全部回滚。合法编码冒号及64字标识通过实际HTTP验证。

resolve 记录已有的恢复/损失结论与凭据，不执行外部扣款。客户身份、钱包、核销和受信任系统发放保持原入口；员工撤权或接管族 STOPPED 不停止已经受理的履约事件。回退需要识别 V61 两个族的兼容版本，V49—V61 已执行迁移不得改写。

CentralEntitlementMySqlTest 的7项专项测试已通过，覆盖真实订单域预留/确认/事件/核销/冲正构造的欠项（不宣称HTTP整单退款）、两个独立写权限、审计回滚、幂等、事实竞态、撤权/STOPPED/503及客户兼容。完整回归和跨进程结果由 auth `CE05_MARKETING.md` 记录；页面为后续 CE05-E2。Java formatter/静态分析未配置及既有本地准入预算限制保持。


## CE05-E1验收

CE05-E1本地DONE：ENTITLEMENT_DEFINITION/ENTITLEMENT两族、四独立权限、真实grantId/version、旧回执前路由/事实/期限复核与同事务身份审计，V61已应用不可改。完整460项455PASS/5既有skip，新增7项全PASS；真实独立58007c181423（10.254.107.0/24）477PASS，无浏览器。37工具/252入口/122能力/34角色、两仓hygiene及auth1/commerce8源码摘要一致。下一E2权益定义/实例页面；其余CE05—08和原生产2HOLD未完成。

|验收|结果|私密证据|
|---|---|---|
|本地真实MySQL/全仓|460项455PASS/5既有skip；7权益专项、会员并发原4方法和3架构测试通过|commerce-contracts/entitlements-owner-verify-final.log|
|真实独立授权与业务|477PASS，两读两写独立、实际Owner、编码冒号、原键/新键、跨租户、撤权旧回执拒绝与停机503|p6/rehearsal-58007c181423/result.json|
|账本与客户兼容|定义3+实例2恰5身份审计；RECOVERED/WRITTEN_OFF各一条units2；实际积分兑换事件发放，撤权后客户核销2/余3、重试不重复、超扣拒绝、积分余50|同目录检查点与entitlement-fixture-origin.json|
|源码/质量|37工具/252入口与hygiene通过，auth1/commerce8最终摘要匹配；Java formatter/静态分析未配置限制保留|commerce-contracts/entitlements-owner-{source-sha256,auth-hygiene-final,commerce-hygiene-final}.json|

跨进程待补偿数据为明确的隔离SQL夹具，不宣称HTTP整单退款；MySQL专项从订单域真实预留/确认/事件/核销/冲正产生欠项。客户积分兑换、受理事件及核销为真实HTTP链路。自有进程finally停止，原8602未切换，无新worktree，全部私密证据和隔离库保留。

首次两项新夹具错误已修；次轮既有MemberGrowthTest五秒屏障超时，定向两次被父POM显式failIfNoTests拦截。均保留原日志，最终原样完整重跑通过；未放宽业务/测试预算、数据库约束或构建门禁。技能状态implementation-validation COMPLETED/PASS；update-progress-docs E1 DONE（本地）。Git/CI另记。
