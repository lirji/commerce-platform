# LP10 历史设计笔记：批量/定时经营与可信渠道价格

本文件保留LP09结束时的设计推演。LP10已按LP10_CONTRACT.md实现并提交77628be；运行和验收以正式契约及LP10_EVIDENCE.md为准，以下不再作为待办。

## 定时批量

catalog拥有持久job/items。动作调价/上架/下架；固定store、创建人真实Actor、SKU和预期revision，每批1–100项，不跟随查询结果变动。runAt现在或未来30日，deadline有限；支持取消未提交项、失败隔离恢复、逐项成功/版本冲突回执，不能声称取消会回滚已生效商品。

逐项事务锁job→SKU，预读当前revision；不匹配直接记录CONFLICT并继续其他项，不把SKU CAS冲突当成功。正常写复用ProductOperations的价格/状态/历史校验与入口。若复用Commands.run，注意异常导致rollback-only，不能吞后再提交回执；版本冲突在进入嵌套命令前明确预检并已持SKU锁。权限重新验证创建者的当前门店CATALOG授权，撤权不继续执行；admin权限不伪装成原operator。每租户每轮小批、全局租户公平，DB持久游标/次数/退避、最多5次隔离；人工重试有版本/原因。

## 渠道价格

不能把请求体channel当可信客户端，避免会员自行指定低价渠道。建议扩展Actor可空/默认WEB的销售渠道，来自platform_credential新增受控列（WEB/MINI_APP），保留3参数构造和旧凭据默认WEB。只通过现有受控凭据配置/种子/部署运维建立MINI_APP身份，不新增任意会员切换渠道入口。Quote.Request不接受channel字段，客户端传入继续400；渠道固定在认证身份。

catalog维护store/SKU/channel当前价格版本、ACTIVE/DISABLED与生效窗口、审计历史；SKU锁下CAS更新。报价从CatalogApi批量读取有效渠道价，未配置/过期/禁用回退基础价；报价有效期不超渠道价结束。Quote.View记录实际channel，Line记录channelPriceVersion（兼容旧JSON缺省0），SKU revision仍表达商品基础版本；Order复制真实价格快照。渠道价调整不改已生成有效报价，沿用既有5分钟快照语义。

幂等要包含可信渠道上下文，同时保持WEB旧请求hash兼容；可对非WEB命令加入服务器渠道包装，不能让同actor跨渠道重放到错误报价。商城检索/详情和报价应一致使用认证渠道价，筛选也应使用生效价，避免只在结算出现差异。旧接口与无渠道价租户保持基础价。

使用现有MySQL/worker，无新中间件。真实DB与HTTP验证调度未到时、部分版本冲突、重复/并发推进、取消和撤权、失败恢复；渠道权威身份/不能自选、期限/回退、历史报价保留、前端列表与成交一致。
