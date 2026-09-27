# 回归、负例与浏览器

## 最终验证

`./scripts/build.sh` 最后一轮从 clean 开始，前端重新构建并打包进 Spring Boot jar，退出码 0、BUILD SUCCESS：

| 范围 | 最终结果 |
| --- | --- |
| commerce-app | 279 run、0 failures、0 errors、5 configured skips |
| architecture | 3/3 |
| marketing | 27/27（12 决策、15 规则） |
| order domain | 45/45 |
| shared kernel | 3/3 |
| 受影响浏览器 | 2/2，最终 jar，17.3 s |

相比 Phase 6 应用 271，新增七项 MySQL 测试及一项报价旧 enum 契约回归。七个 MySQL 场景：过期候选不挤占有效上限、竞争预览与 quote 同赢家、券预留/支付唯一发放、取消释放、最后额度竞争、发券事务回滚恢复、双实例唯一发券。原 CREDIT 成功、MISS/UNKNOWN、额度不足、重复触发、隔离恢复、历史 v1/v2、回退及 Phase 2–5 用例均保留在全套中。

五项配置跳过属于既有条件测试，未把跳过写成通过。没有执行未经授权的生产部署、外部支付/WMS 或真实灾备演练。

## Mutation 与高价值负例

临时删除 `CampaignConflictResolver.bestOf` 的 campaign ID `thenComparing`，执行 clean marketing test；直接逆序候选断言失败，准确为 1 failure。通过 finally 恢复源码，再 clean verify：27/27。最终完整构建也包含该强化断言。未遗留 mutant，没有声称执行完整 mutation campaign。

额度 30/120 多用户争用、最后一份双请求、其他租户详情 404、会员管理操作 403、未审批发布拒绝、发布重检失效人群、重复规则分支拒绝均有现有/新增回归。最终 NEW jar 的 `coupon-enabled=false` 实际配置创建返回 **409 CONFLICT**，无新券活动写入；开关 true 的成功业务和 OLD 忽略 coupon 的反例共同证明门禁必要。

## 保留失败记录

- 第一次实现全套出现无权益订单 Long 解箱 NPE、预览缺省 boolean 400，均已修复并在完整构建通过。
- 第一次最终干净构建应用 278 中两个原旅程用例失败（RUNNING 而非完成/隔离）；单独复跑 2/2，后续两个完整 clean 构建均无失败。没有定位可靠根因，不将这次失败删除或说成产品保证。
- 浏览器首轮 1/2：入库确认后，退款由异步事件产生，测试立即打开空列表并等待不存在的沙箱按钮。截图证明列表为空。测试现在先 poll 当前订单的真实退款记录，再进入退款页面；最终 2/2。没有静态 sleep、改产品流程或调用假接口。
- 压测初版以双 pump 都返回 0 判断排空，实际领取/预算可能暂时无进展。已改为查 30 条 GRANTED；全部最终负载以数据库业务结果验收。

本轮没有重跑 Phase 5 全部浏览器集合；其中四项原有失败仍属于历史边界，不将两个受影响场景冒充全 UI 套件。

本地原始构建/浏览器日志在忽略目录 `.local/phase7-build-final.log`、`.local/phase7-browser/`；已纳入版本可审查的性能与计划原始结果在本证据目录 `results/`，不含令牌或密码。

## 最终枚举兼容收尾

新增 `LegacyQuoteSnapshotTest.rollingQuoteTraceUsesOnlyOldEnumValuesUntilEveryNodeIsUpgraded`，旧枚举往返和新值拒绝均验证。最后完整 clean 构建应用 279/0/5、架构 3/3；最终 jar 的浏览器 2/2（17.3 s）。真实 OLD 对最终 NEW 的双合格活动报价：读、下单、取消均 200，赢家与金额相同。修复前 OLD 读取扩展值实际 500，门禁默认 false；扩展解释启用后 OLD 历史回退仍不支持。没有改写任何历史报价来伪造兼容。

最新构建日志 `.local/phase7-compat-final-build.log`、最终浏览器 `.local/phase7-compat-browser-final.log`；以前成功/失败批次仍保留，不将旧批次当最后 jar。

## 后续 Git 交付验证

用户后续明确授权提交并推送远程 main，再开启 Phase 8。本轮重新执行 `./scripts/build.sh`：clean UI 构建与 Maven verify 成功，应用 279 项、0 failures/errors、5 configured skips，架构 3/3、marketing 27/27、order 45/45、shared-kernel 3/3。jar 已核对 UI 入口，无本轮 benchmark 辅助 class。原始日志位于忽略目录 `.local/phase7-git-delivery-build.log`。

为核对分批提交独立性，将首批暂存树导出到隔离快照，针对 Money、MarketingDecision、RuleEvaluator、OrderLifecycle、LegacyQuoteSnapshot 执行 verify：78 项零失败，全部依赖模块可构建。初次选择器未包括 shared-kernel 必需测试，被 failIfNoTests 门禁拒绝；补全模块测试选择器后通过，没有改变项目门禁或产品代码。

本次最终 jar 受影响浏览器再次通过 2/2（18.1 s），覆盖真实收款/退款/CREDIT 冲正及可视规则/低代码审批发布；日志 `.local/phase7-git-delivery-browser.log`。
