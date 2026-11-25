# 运营体验改造进度

首批任务分支 `fix/operations-experience-foundation`，基线 `a5f1b37`。续批任务分支 `feat/operations-experience-queues`，基线 `46a294c`。用户已授权按 [实施切片](../../design/operations-experience/IMPLEMENTATION_SLICES.md) 连续执行。

| 切片 | 状态 | 证据 |
| --- | --- | --- |
| S1 中央组织入口 | DONE | 真实 HTTP 静态壳、匿名权限边界和组织落地验证通过 |
| S2 中央运营页面筛选 | DONE | 中央公开 DTO 的 100 条分页、刷新与重置通过 |
| S3 中央目录状态 | DONE | 商家/门店独立切换、重置和旧书签复验通过 |
| S4 SKU/SPU 检索 | DONE | 本机两个真实查询 HTTP 200，各自影响对应页签 |
| S5 订单范围及紧凑布局 | DONE | 390/320px 已实测，日期折叠、刷新和详情返回通过 |
| S6 交易查询与字段 | VERIFIED | `0f89ed4`，9 项实库测试及 2 项打包浏览器测试通过 |
| S7 商品与营销 | VERIFIED | 41 项实库与调度测试通过，商品和营销界面已构建 |
| S8 跨域追踪 | IN_PROGRESS | 复用现有各域独立业务能力和稳定原单号 |
| S9 异常工作区与治理 | TODO | 可执行本地能力；正式渠道与治理依赖另记 |

测试本地证据在 `.local/operations-experience-delivery/`。既有 8602 是审计基线，运行新制品使用临时验证环境。

首批已通过 78 套件的 Maven 校验（562 项中 557 通过、5 条按既有条件跳过）、打包浏览器回归（24 通过）及只读实际数据探测。Git 交付基于这些变更和证据，精确提交与 CI 回执放入本地证据目录。验证细节见 [验证记录](TEST_RESULT.md)。

续批证据在 `.local/operations-experience-queues/`。继续实现 S8 授权关联后进入 S9 的本地异常队列。
