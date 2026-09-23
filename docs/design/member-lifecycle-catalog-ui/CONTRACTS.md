# 契约 v1

沿用 /v1，Actor 权威租户；ADMIN 配置/考核、MEMBER 仅本人，OPERATOR 无会员资金权限。POST 要求 Idempotency-Key；相同键同体重放、异体冲突；INVALID_INPUT/NOT_FOUND/FORBIDDEN/CONFLICT 沿用项目 HTTP 映射。所有集合有界，游标稳定。

## LP01 周期等级

- POST /v1/admin/member-cycles/policies：`{version,effectiveFrom,periodDays,levels:[{code,minimumGrowth}]}`。版本>0、周期1–366日、1–8档、首档0且阈值严格递增，代码唯一。生效时间现在或未来一年，不可回溯修改；历史成长策略仍定义每元成长率。
- GET 同路径：after=0,limit=50，历史不可变策略。
- POST /v1/admin/member-cycles/{memberId}/evaluate：对账当前周期，重复不重复发事件。
- GET /v1/admin/member-cycles/{memberId} 与 /v1/members/me/cycle：`{memberId,enabled,policyVersion,cycleStart,cycleEnd,currentGrowth,retentionGrowth,memberLevel,version}`；未配置 enabled=false、边界null，无隐式写。
- `member.cycle.assessed.v1`：`{memberId,policyVersion,cycleStart,cycleEnd,memberLevel,currentGrowth,retentionGrowth}`，每次首次周期或等级变化发出；`member.level.changed.v1` 保持兼容。级别不变但周期续期也产生考核事实供权益续发。
- 内部贡献：来源订单及人工调整幂等；订单成长固定按原策略，发生时间取下单时间，退款更新净贡献。历史已存在成长订单不回填未知日期；启用前历史不参加周期考核。

## 后续契约约束

LP02 等级权益绑定、LP03 积分及 LP04–LP11 的具体 DTO/状态/接口须在各片实现前由设计 pass 补齐本文件，不能边写调用方边猜 JSON。当前只准进入已具备正式契约的 LP01。总体目标不因分片推迟而删减。
积分金额 CNY，整数积分，未配置禁用。报价不锁积分，订单原子冻结，支付 UNKNOWN 不释放，零现金订单也核销；退款按原商品分配积分，不能兑换现金。过期先到期先消费，已消费返奖冲回形成待偿债务而非负可用。
发券/权益使用可信来源且唯一，运营定向发放不能伪造会员身份。效果展示来源、时间窗、退款和完整已知成本；无曝光或对照组时不显示伪造转化率/增量收益。

## LP02 等级权益

- POST /v1/admin/member-cycle-benefits：`{bindingId,policyVersion,level,storeId,validUntil,benefits:[{benefitId,version}]}`，ADMIN+幂等；每策略/等级至多一个不可变礼包，1–8个不重复权益；级别必须存在于周期策略。定义窗口必须覆盖从策略生效时间至 validUntil，所有权益归属于 storeId。
- GET /v1/admin/member-cycle-benefits?policyVersion=1：有界最多8档，不隐藏已失效绑定。
- POST /v1/admin/member-cycle-benefits/{memberId}/grant：幂等补发当前有效等级，响应 `{memberId,grants:[EntitlementApi.View]}`。仅 ACTIVE 会员，余额以权益发放消费者最终状态为准，REQUESTED 不显示“已到账”。
- 首次周期/新等级事件消费时读取权威最新考核；旧周期/旧等级的迟到事件不发奖。来源键为会员+策略+周期+等级+权益引用的摘要，数据库唯一；补发/重试/降级再升级不会重复扣配额。
- 礼包发放全有或全无，配额不足回滚；定时事件使用已有5次重试/隔离及人工重试入口。降级不追溯撤销已授予权益，仍按原有效期享有，下一周期按新等级发放。已过 validUntil 不发放，历史周期不补发。
- 新权益来源 LEVEL；POINTS 等待 LP05 再启用。原 ORDER/JOURNEY 的语义和接口兼容。
- 前端：成长页面增加周期考核、策略配置、等级权益配置与补发；会员看到周期起止、本期/保级成长、等级和真实权益钱包。enabled=false 显示“尚未启用或尚未完成首次考核”，不编造进度。
