# 固定人群规模边界

现有固定快照采用 `marketing_audience_snapshot` 与复合主键成员表，成员名单 API 创建上限 500，数据库快照计数 CHECK 上限 100,000。SMALL 100、MEDIUM/LARGE 500 的点查测量见 `02-performance-decomposition.md`。大活动可引用同一固定快照；报价准备对去重的人群版本一次批量查来源，不为每条规则访问数据库。

当前可确认 `SMALL_FIXED`。`LARGE_FIXED`、`DYNAMIC`、`EXTERNAL_SEGMENT` 的业务尺寸、刷新频率和一致性要求没有获批数据；保持固定 500 边界。大人群若成为必需，优先评估数据库分批导入与成员表读路径，并明确定义快照发布时间和水位；目前不引入 Bitmap、Redis、ES 或 CDP。动态人群不得暗换固定快照的历史解释。

## 超过公开上限的存储探针

直接在专用 benchmark 库导入 50,000 成员快照（数据库 CHECK 允许至 100,000，API 仍为 500）。实际复合主键点查 500 样本 P50/P95/P99 0.360/0.653/1.301 ms；并未出现全名单加载。69 节点、20 人群引用在真实 quote 中仍为一次批量 membership 查询。

结论为 `KEEP_CURRENT_MODEL`：数据库 membership 表本身没有在这个样本暴露规模瓶颈，API 导入、更新治理、真实名单语义与业务峰值还未确定，不把存储能力等同公开产品支持。没有新增 Redis/Bitmap/ES/CDP。真正 LARGE_FIXED、DYNAMIC 或 EXTERNAL_SEGMENT 需求仍须产品确认；目前已支持的 SMALL_FIXED 使用发布快照和明确水位/有效期，执行保留固定版本。
