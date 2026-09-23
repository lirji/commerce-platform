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

### S9b 已冻结契约

- PageDefinition={pageId,version,title,storeId,sections,actions}；1..8个Section={id,title,source}，0..4个Action={id,label,kind}，两组标识全局唯一。
- source白名单：CAMPAIGNS、JOURNEYS、BUDGETS读取租户级最新前20项；COUPONS、ENTITLEMENTS读取页面storeId的定义前20项。均通过领域API，响应明确bounded=true，分页详情进入对应业务页。component固定为只读数据表与类型化动作表单，不接受脚本、SQL、HTML、URL或自定义字段路径。
- kind白名单CREATE_CAMPAIGN、CREATE_COUPON、ENROLL_JOURNEY。动作入参ActionInput={campaign?,coupon?,enrollment?}，恰好一种与kind匹配。优惠定义/活动必须属于页面storeId；入组沿用旅程API权限、版本与有效期检查。调用原领域命令，不复制业务逻辑。
- ADMIN POST/GET /v1/admin/ops-pages创建/列出最新定义；GET /{id}/versions（最多50版本）、POST /{id}/{version}/{submit|approve|reject|publish|pause|rollback}带Idempotency-Key与expectedVersion。只允许DRAFT→IN_REVIEW→APPROVED→PUBLISHED；rollback仅重新发布曾发布的PAUSED版本。同pageId只有一个发布版本，版本内容不可修改。
- POST /v1/admin/ops-pages/preview接收完整定义，验证DSL并读取真实数据，零命令/业务写入；GET /v1/admin/ops-pages/{id}/render仅渲染当前已发布版本；POST /{id}/{version}/actions/{action}只能执行当前发布版本已声明的动作，必须幂等/审计并复用服务端管理员校验。
- 旧页面回退不撤销已经提交的业务操作。预览也不能执行动作；前端预览明确禁用提交控件。客户端隐藏入口不作为权限证据。
