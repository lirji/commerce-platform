# 技术未知与决策

| ID | 问题/现状证据 | 决策及原因 | 风险/代价/退出 |
|---|---|---|---|
| T1 | MemberGrowthService.apply 按终身成长覆盖等级 | BUILD 会员周期策略与考核投影；启用后周期服务成为等级唯一写规则，保留旧成长累计 | 旧租户无周期策略完全兼容；启用需显式发布 |
| T2 | 现有券/权益、订单同库事务，尚无积分 | BUILD 独立积分账本、批次及冻结分配；复用 Commands 事务和数据库约束 | 必须检验余额、退款及到期交错，不能仅 Mock |
| T3 | EventWorker/EventDispatcher/SegmentApi/JourneyApi 已存在 | REUSE_EXISTING 持久化任务、Outbox/Inbox 与有界扫描；不引入新流程引擎或 MQ | 日历触发增加持久化游标与来源唯一约束；未来吞吐证据需要时才外置 |
| T4 | CatalogApi 已有 SPU/SKU 和 revision | BUILD 数据库经营模型和任务；复用权限及 CAS | 检索先用有界 SQL，不宣称全文相关性；ES 推迟到有实测需求 |
| T5 | React/AntD 已稳定且用户要深色 | REUSE_EXISTING AntD darkAlgorithm、统一 token/CSS、真实接口数据 | 不升级依赖、不增加组件体系；对比度/键盘/窄屏需浏览器验证 |

不存在新增第三方主要组件，无需虚构兼容矩阵或技术性能。以上来自本仓库源码；既有运行时版本复用 pom/package-lock。获取方式 BUILD 仅限业务差异化，通用鉴权、迁移、运行环境继续复用。
