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

## LP03 积分账本

- POST /v1/admin/member-points/policies：`{version,effectiveFrom,earnPerYuan,expiryDays,spendEnabled,pointsPerYuan,maxDeductionBps}`。每元获取0–1000（最多2位小数），有效天数1–366；兑换率1–100000积分/元、抵扣上限0–10000基点；spendEnabled默认false。不可变版本，生效时间现在至未来一年。获取按原下单时间选策略，完成订单按实际现金净消费向下取整奖励，退款仍用原策略。
- GET 同路径 after/limit；GET /v1/admin/member-points/{id}、/v1/members/me/points：`{memberId,available,held,debt,credit,version}`。available=max(0,未过期剩余credit-debt)，held不可重复消费；读取按真实时间排除过期积分，即使到期任务延后也不可消费。
- GET 对应 /ledger：`[{sequenceId,action,sourceId,delta,available,debt,policyVersion,reason,createdAt}]`，after=0/limit<=100；delta为净资产变化，过期已失去的积分退款免再次扣回并记录0变动原因。
- POST /v1/admin/member-points/{id}/adjust：`{expectedVersion,delta,reason}`，整数±10亿；正向需有效积分策略，负向优先扣未过期积分，不足形成debt。单命令最多处理200个来源批次，碎片超过上限且尚有未处理余额时拒绝并提示分批，不能误建债务。
- POST /v1/admin/member-points/{id}/expire：有界处理最多100个已过期批次，返回最新Wallet；定时器每轮20批。GET无隐式写。积分仅会员本人可读、管理员可配置/校准；冻结/注销不可人工赠分，真实退款仍可冲回。
- 来源：订单/退款事实只走内部端口；每订单一份贡献和奖励批次，退款唯一。退款先到先记来源，完成再按净现金奖励；同一来源金额或会员不一致拒绝。单来源奖励<=10^12积分，账户债务<=9×10^15。
- 入账有效期为首次完成奖励入账时刻+expiryDays×24小时，UTC精确毫秒；退款不延长。已过期未使用部分免扣，剩余先扣原批次可用，已经消费或抵偿过欠项的部分形成待偿扣回；后续新增积分优先抵偿欠项。不可兑换现金。
- LP04/LP05尚未接入消费，LP03仅发布策略和独立获取/退款/到期账本。held字段为已批准后续抵扣分配预留且本片恒0；不因此宣称抵扣/兑换完成。

## LP04 积分抵扣与售后

- POST /v1/quotes 增加可选 `redeemPoints`（0至10亿整数，缺省0）；旧请求保持不变。按活动→券→积分顺序，积分最多抵扣剩余应付×maxDeductionBps，金额按分向下取整；实际消耗积分为抵扣分×pointsPerYuan/100向上取整。报价可将请求上限缩减为真实可用额，页面必须展示实际值；不预占。
- Quote.View新增可空 `points:{policyVersion,points,discount}`；Quote.Line新增 `points`（缺省0）、`pointDiscount`（缺省0.00）；FundingLine新增pointDiscount。积分补贴单列来源但计入平台承担，活动预算仍只记原活动部分。订单Line原样冻结新字段，旧JSON缺省兼容。
- 下单在会员锁下重新验证积分策略版本、消费开关、余额/期限及报价经济计算；规则已改变或额度不足则409并回滚报价消费。积分冻结先于券/活动预算/库存，以保持锁顺序。一个订单最多分配200个来源批次，FIFO按期限和lotId；报价只使用该上限内可分配量。
- 支付确认才将RESERVED→CONSUMED；零现金订单在创建事务内确认。明确取消才RESERVED→RELEASED；支付UNKNOWN/CLOSING继续冻结。重复终态无重复账本。所有持有、批次分配与订单命令同事务。
- 积分按各SKU的积分抵扣分以累计差额分配，整数尾差在确定顺序的后续行吸收；部分退货再按原SKU数量累计差额分配。总返还不超过原积分，现金只退原现金实付，折扣不变成额外现金。
- Aftersale.Line增加points（旧快照0）。成功退款事实推进售后COMPLETED时在同事务返还积分，零元退款同样处理；REQUESTED/WAIT_RETURN/UNKNOWN不返还。售后caseId唯一防重，返还来源FIFO归回原批次，不刷新有效期。
- 释放/返还先抵偿待偿扣回；其余在原期限内恢复可用，已过期部分失效，账本记录原积分数及失效数，不能伪称全部到账。该规则避免原奖励已被退款扣回、在途冻结又取消时留下虚假欠项。
- Points.Entry新增held快照（旧记录0），动作增加HOLD（净变动0）、SPEND（扣除）、RELEASE（只扣除到期失效部分）、REFUND（扣除失效后的恢复量）。不改已有动作语义。
- 前端：购物袋可填写使用积分上限，展示实际消耗/抵扣金额/剩余现金；订单/售后展示积分分配，会员账本可辨识冻结、核销、返还及到期失效。禁止客户端计算权威金额。

## LP05 积分兑换

细契约见 [LP05_CONTRACT.md](LP05_CONTRACT.md)，包含受控券模式、兑换目录、原子扣分受理、限额和回执。

## LP06 会员行为与人群

细契约见 [LP06_CONTRACT.md](LP06_CONTRACT.md)。前端交互记录使用独立后台请求，失败可见但不清空购物袋；不把交互信号直接当资金奖励依据。

## LP07 定向发券

细契约见 [LP07_CONTRACT.md](LP07_CONTRACT.md)。固定人群快照、受控券版本及逐会员持久检查点；停止与撤销是独立操作。
