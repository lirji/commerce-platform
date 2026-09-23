# S9 持久营销旅程与低代码运营

分为S9a旅程、S9b低代码页面，均沿用有界本地MySQL任务，无新增MQ或外部触达。用户已明确外部联调后置。

## S9a 旅程定义与运行

- 版本化Journey定义：journeyId/version/storeId/name/trigger(MANUAL|ORDER_PAID)/validFrom/validTo/maxDurationSeconds/entry/nodes。最多32节点，无环，所有节点可达且终止于END，引用必须存在。maxDurationSeconds 1..2592000。
- 节点为WAIT(seconds1..604800,next)、DECIDE(rule,yesNext,noNext)、GRANT(benefitRef,next)、NOTIFY(title,body,next)、END。条件仅允许可信memberLevel与orderAmount；UNKNOWN直接终止为RULE_UNKNOWN，不进入发放分支。通知为站内消息，文本不解释为HTML或脚本。
- ADMIN /v1/admin/journeys POST/GET；/journeys/{id}/{version}/submit、approve、reject、publish、pause，带Idempotency-Key/expectedVersion。已发布内容不可变；同定义只激活一个版本。节点权益绑定定义版本且同店，版本不得被在途实例替换。
- ADMIN POST /v1/admin/journey-instances {journeyId,version,memberId,eventKey}只启动MANUAL已发布有效版本。ORDER_PAID由可信order.paid.v1订阅自动启动，单次最多10个已发布旅程；超上限隔离而非静默截断。零元order.ready.v1不冒充已支付触发。
- 实例唯一tenant/journey/version/eventKey，保存member/order来源、定义版本、当前节点、dueAt/deadline/steps/attempts/result/并发版本。每事务只执行一个本地节点；WAIT把下一节点和未来dueAt同事务保存，进程重启继续。
- 有界公平轮转：每轮最多4租户×5实例，行锁SKIP LOCKED与条件状态更新。失败回滚节点/效果，另事务最多5次退避后ISOLATED；管理员retry可重放。超deadline标TIMED_OUT。取消阻止后续节点，不能撤销已送站内消息或已发权益。
- GRANT使用tenant/instance/node稳定幂等键，写入权益意图后异步授予；扩展权益来源类型ORDER/JOURNEY，不能把旅程ID伪装成订单ID。
- 原订单全退会先取消关联旅程，再冲正所有关联权益；迟到OrderPaid或节点执行前须复核全退事实，避免退款后新建/发放。没有订单来源的手动旅程不受订单退款影响。
- GET /v1/admin/journey-instances、POST /{id}/cancel、/{id}/retry、POST /v1/admin/journeys/pump；GET /v1/notifications只返回本人站内消息，GET /v1/journey-instances只返回本人实例。
- 真实渠道触达未配置，不向用户的外部账号发送消息。站内消息是产品数据库数据，保留唯一效果键与来源。

## S9b 低代码运营（随后实施）

运营页面DSL只允许固定组件、字段和数据源/动作白名单；必须支持预览、版本审批、发布与回退至旧版本。通过真实领域API读取/执行，禁止任意脚本/URL/SQL。具体schema在该子片实现前冻结。
