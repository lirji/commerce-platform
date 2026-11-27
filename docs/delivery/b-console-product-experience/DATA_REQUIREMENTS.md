# 后续数据接口与字段需求

下列为待确认的读取模型。统计应和同主体、同授权门店、同条件的列表请求核对。分母、数据窗口、稽核时点和单位应随响应返回。

| 使用页面 | 待补能力或服务端字段 | 口径与验收 |
| --- | --- | --- |
| 订单、履约、售后、支付、退款 | 按状态完整计数，返回 `totalCount`、`statusCounts[]`、`asOf`、`scope`。异常队列增加 `pendingSince`、`oldestPendingAt`、`slaThreshold` 和到账或渠道核对时间 | 计数绑定实际角色、投入查询的门店及原筛选。允许用同条件下钻对账，区分成功、未知与读取失败 |
| 总览和门店 | 同店与授权店组聚合 `window`、`comparisonWindow`、`timezone`、`daily[]`、`asOf`、`coverage`。门店目录需要按营业状态返回全量计数 | 明确经营日、同长度同期以及迟到退款归属。前期为零时保留实际金额，变化率无分母时显示不可计算 |
| 商品与库存 | 按实际规格和归属门店汇总，返回 `skuCount`、`outOfStockSkuCount`、`lowStockSkuCount` 及门店 SKU 补货阈值 `lowStockThreshold`、`thresholdSource`、`asOf`。需要批量读取时提供商品标题、规格名称和现行渠道价格 | 按可售口径与权限范围核对。用户可在同条件下筛出真实缺货项。规格和商品数量保有各自维度 |
| 活动与预算 | 活动版本批量摘要 `effectiveStatus`、`endsAt`、`budgetId`、`limit`、`held`、`spent`、`available`，另需活动业务自定义预警策略 | 同活动固定版本绑定预算。支持正常到期、无限额、额度为零、授权不足，以及货币一致性 |
| 权益与发券 | 权益状态分组和补偿待办总计。发券增加冻结批次的 `eligibleRecipients`、`uniqueProcessed`、`retriableFailures`、`terminalFailures`，撤回方向单独统计 | 目标数需来自固定人群口径。按批次和方向核对，重试、跳过、撤回、已保留分别计量；当前已处理构成可由现有回执验证 |
| 营销效果 | 增加真实曝光、触达与转化分母，返回 `attributionWindow`、`denominatorType`、`windowComplete` 和外部渠道回执时点 | 活动现有已付比例只描述同统计范围内的下单与已付。跨渠道转化、同比环比和归因需要额外口径 |
| 各游标列表 | 如需任意页跳转，提供受控的页码协议，或可在同查询快照下定位页首的授权游标。返回 `totalCount`、`pageSize`、`hasMore` 或 `nextCursor`，约定取消查询、数据并发变化和游标过期的恢复 | 保留当前筛选、稳定排序、权限校验。协作商品已返回真实总数，但其他未访问页没有定位游标。一般游标列表当前提供已访问位置 |

数据源、时间范围、分页类型和权限由原端点决定。本期使用既有契约交付交互和真实数据展示。
