# LP07 定向发券与相对有效期

## 券定义扩展

Coupon Definition增加可选`validityDays`（null或0为原固定窗口，1–366为自发放时起有效天数）。validFrom/validTo是发行窗口；相对券钱包validFrom=服务器发行时刻、validTo=发行时刻+天数，可超过发行截止日。报价、下单、取消和全额退券均读钱包固化期限，不续期。旧构造/JSON/hash保留兼容，V31新增coupon.valid_from默认null，旧券读取COALESCE至定义时间。

受控来源新增TARGETED；仅SOURCE_ONLY定义用于定向发放。额度仍全定义共享。CouponApi以(sourceType,sourceId)去重，source固定SHA(batchId/memberId)。人工撤销只影响AVAILABLE券；HELD/USED/EXPIRED明确保留，后续退券仍按原规则处理，不声称已撤销。撤销不返发行配额，不影响积分兑换来源券。

## 持久发券批次（marketing-automation拥有）

- POST `/v1/admin/coupon-deliveries` Create `{batchId,storeId,name,definitionId,definitionVersion,audience:{id,version},deadline,minIntervalHours}`。管理员幂等命令，定义/人群版本固定，deadline在未来7天内且不晚于人群过期/券发行截止，minIntervalHours 1–720。
- GET `/v1/admin/coupon-deliveries?storeId=&after=&limit=`：`View {content,status,processed,issued,skipped,revoked,kept,cursorMember,revokeCursor,attempts,errorCode,version}`；limit1–100。
- GET `/{id}/recipients?after=&limit=`：`Recipient {memberId,status:ISSUED|SKIPPED|REVOKED|KEPT,couponId,errorCode,createdAt}`。已占用/用掉/到期券撤销标记KEPT，可从钱包真实状态进一步核查。
- POST `/{id}/control` `{expectedVersion,action:CANCEL|RETRY|REVOKE,reason}`：RUNNING或ISOLATED可CANCEL；ISOLATED可RETRY未过deadline；COMPLETED/CANCELLED/ISOLATED/EXPIRED可REVOKE。状态及次数有CAS与审计，取消不回滚已发送。
- POST `/v1/admin/coupon-deliveries/pump`：推进当前租户最多20个收件人；后台每轮4租户轮转，每租户最多一个批次。无新增调度基础设施。

状态：RUNNING→COMPLETED/CANCELLED/EXPIRED/ISOLATED；ISOLATED→RUNNING；终态可进入REVOKING→REVOCATION_DONE；撤销执行错误也可ISOLATED，保留原执行mode供RETRY恢复，撤销不受原发行deadline限制。

每收件人独立事务：锁批次→锁会员→当前读频控→发行券→回执→检查点。同租户会员全定向批次共享nextEligibleAt，后续批次不能降低前批次已设置的冷却期。冻结/注销或频控中记SKIPPED并推进；发券额度不足/系统失败回滚当前收件人并最多5次退避重试，隔离后运营可取消或明确重试。租户间与会员间额度隔离。

人群数据通过MarketingAssets新的有界成员读取API，不跨模块读表。发放前检查固定快照仍有效；完整快照不可变，任务不会静默追随最新定义。未发送对象不复制入任务表；已执行回执可审计。服务重启由表内mode/status/cursor恢复，任务进度与业务效果同事务。

## 验收

真实MySQL验证相对期限、过期退券不复活、固定人群、重复pump/命令、跨实例竞争、频控、冻结跳过、配额失败无游标前进、停止/恢复/撤销、隔离与租户权限。浏览器创建批次、推进、查看真实回执及撤销结果。任务属于自动化域、券权威属于benefit，沿用MySQL与原Worker。
