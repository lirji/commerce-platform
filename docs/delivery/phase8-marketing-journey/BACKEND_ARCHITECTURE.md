# Phase 8 后端架构

计划已获用户一次确认，以下工程选择属于该已授权边界。Brownfield模块化单体，Java/Spring/MyBatis/MySQL8.4与既有dev_infra不变；无需新的技术依赖或工作流产品。

## 数据权威与不变量

Journey拥有definition、instance、step history与站内notification；rule/facts属marketing/member，CREDIT/coupon属benefit。app只暴露协议、装配指标和调度。Journey经公开API调用规则/权益，禁止直接访问benefit persistence。

- definition JSON仍为原typed结构，entry是唯一逻辑起点，next/yes/no是显式边；schema 1语义保持，无新node enum写入旧定义。内容只能新建版本。32节点DAG、规则8层/128节点与原窗口上限保留。
- instance绑定固定definition version，不随发布或pause移动；version内同业务触发唯一；支付来源orderId，会员事件eventId，生命周期来源原稳定key，原跨版本窗口频控保留。
- 新step_execution按tenant+instance+transitionVersion唯一，序号为原steps+1。短事务内记录EXECUTING，再更新最终状态与next/outcome/actionRef；外部不可逆action不在本期。
- 同库动作效果、step history与instance advance原子提交。进程在动作效果后kill会回滚全部未提交效果。已提交效果有原领域source hash(instance/node)幂等边界；不宣传分布式exactly-once。
- 非瞬时失败保持原5次隔离；瞬时失败延后不消耗毒预算，绝对deadline终止。独立失败事务只有锁定实例版本仍相符时更新失败记录，避免落在另一执行器已推进节点上。
- 人工retry/cancel仍由Commands+RecoveryAudit，retry不重放历史；definition pause/publish是停/恢复新入组，既有实例继续。FULL_JOURNEY_REPLAY_NOT_SUPPORTED和IN_FLIGHT_VERSION_MIGRATION_NOT_SUPPORTED。

## 扩展与边界

纯JourneyGraph负责结构校验/索引与确定性后继；JourneyService的分支用例调用现有RuleDecisionPort；小型显式JourneyActions registry只支持GRANT/COUPON/NOTIFY，unique map无fallback。create和publish都验证结构及固定权益引用。preview与runtime使用同一图/规则方法，预览绝不调用registry执行动作，遇WAIT标FUTURE_DEPENDENT。

节点事实在当前执行时间准备一次；orderAmount为来源订单payable，不伪造入口快照，member facts为当前可信事实。historical decisions存Truth、startedAt/completedAt、next和版本，完整未来可变Facts不复制；可解释当时选择但不宣称能重建所有历史Facts。

## 增量Schema与历史

V44仅增加journey_step_execution以及instance.trace_origin_version nullable。新代码入组明确写0；历史/旧代码入组NULL。历史查询固定读取instance.journeyVersion对应definition，traceCoverage为LEGACY_PARTIAL或COMPLETE，发现缺失step ordinal也为PARTIAL，不回填虚构历史。旧进程仍可操作原表/JSON；混合期历史覆盖可能不完整，待旧进程退出后才承诺新入组完整trace。没有旧迁移改写、状态enum破坏或原数据清理。

## 调度与资源

复用journeys lane、TenantRotation、FailureClass、RetryPolicy、breaker和3线程调度池。每次tenant最多5实例步，另20生命周期扫描步；总200项/500ms。Mapper提供backlog supplier，不新造metrics平台。due SQL当前OR须EXPLAIN验证，在证据证明扫描未来群时再拆成有界UNION/独立范围；不先猜索引。

trace查询cursor transitionVersion、limit<=50；每instance成功history<=33，失败可随着有界生命周期增长，API始终有界，报告增长模型。保留期沿用既有机制关闭默认，本期不删除Journey versions/active instances/trace/审计；没有业务保留期限依据不编造期限。

## 安全与兼容

沿用admin和本人权限，tenant与member校验在service和Mapper；401/403/404不变。现有Node/Definition/Instance及UI契约不变，只加独立只读/preview API。无生产部署或新权限系统。需要权限分离、事件WAIT、实例pause、配置迁移、补偿、fan-out的要求作为后续产品能力，非当前执行阻塞。

## 架构一致性审查

PASS_WITH_ASSUMPTIONS：要求↔现有模块、规则/权益引用、原事务/调度、租户/历史、旧JSON/schema兼容均有明确路径；无新增组件。A1无生产峰值/SLO，实测样本不是生产承诺；A2混合节点未记录的trace不能补造。正式验收后才能将切片DONE。

## 证据驱动的调度实现

50k future OR查询实测扫描全租户50000行约28ms，故正式选择按状态/资格索引的5分支有界UNION，V45新增全局status/time/tenant范围索引供租户发现。每分支最多5行、最终稳定去重选5，旧due/deadline语义不变；当前证据和scale结果见13-scale-backlog.md。
