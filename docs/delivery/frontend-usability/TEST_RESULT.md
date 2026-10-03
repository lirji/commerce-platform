# 前端可读性改造验证

U1—U4验收通过，U5的本地验证通过；Git、精确CI与本机Docker状态由部署记录和当前进度继续维护。基线 `de8d133b5913f67c7c8a76efc3e81c1fc3ce9c2b`；最终源码/测试指纹 `a1766e850d66ffc0df1c2ad6b8a08286803762d4f836979f2c7a2ad5ee3ad382`。

| 检查 | 实际结果 |
| --- | --- |
| TypeScript / Vite | PASS，最终build-delivery.log |
| 真实MySQL全量 `mvn -B -Pwith-ui clean verify` | 74套件，554项：549 PASS / 5既有条件SKIP，0失败/错误 |
| 只读查询专项 | 21项PASS，关键词在LIMIT前过滤、字面特殊字符、状态、时间、门店/租户权限、布尔false |
| 经营/会员/中央浏览器组合 | 42项PASS；中央36路由覆盖1440/390/320px，导航、关闭、未保存输入、未知命令结果与权限失败保持有效 |
| 最后布局配置与查询读取整理后 | 经营/会员22项及中央订单1项PASS；最终真实DTO视觉/筛选3项PASS，包含跨页、上一页、刷新恢复、重置、FROZEN状态、UTC时间及320px详情 |
| 格式与代码卫生 | 64个改动前端文件Prettier check PASS；diff check PASS，卫生门禁无阻断项；未引入依赖或第二设计系统 |
| XML与SQL复核 | 24个Mapper XML解析通过；关键词/状态/时间/启用状态绑定参数，原租户和门店过滤保留，新增SQL无原文插值 |
| 当前打包资产 | 全量验证后顺序clean package；61个dist文件与JAR内当前静态文件逐字节一致 |

截图实际复核了桌面会员密度、手机会员状态与筛选、居中档案详情、中央订单经营字段、手机订单详情，以及中央页面族筛选与分页。按钮保持38px，普通详情保持紧凑宽度；宽表在自身区域滚动。测试夹具只位于Playwright测试边界，正式页面始终读取接口；中央浏览器夹具不冒充实际OIDC或授权集成，权限由真实MySQL测试验证。

私密证据在 `.local/frontend-usability/`：`backend-final-xml/`、`backend-counts.json`、`backend-verify-final.log`、`backend-focused-4.log`、`final-current/browser-results.json`、`delivery-browser/browser-results.json`、`visual-final/browser-results.json`、`artifact-proof.json`、`format-check.log`、`hygiene-current.json`。不提交运行凭据。

历史失败保留：第一轮错误并行Maven覆盖JAR导致验证无效；第二轮仅既有积分并发屏障超时，独立复核与最终全量均通过，未修改积分规则或该并发测试。专项选择包含空测试依赖模块的命令因原POM非空门禁停止，随后顺序install并仅app执行成功，未放宽POM。浏览器旧隐藏分页断言改为明确禁用翻页，新用例纠正测量行选择和正式资格DTO，保留业务保护断言。

能力边界：无可信总数的游标列表不提供总页数或任意跳页；最近50次实际访问轨迹有界保留。沿用项目既有时间/门店/会员/任务等筛选，不为无事实字段伪造控件。尚无仓库统一的Java格式化或独立前端lint任务，卫生工具报告此检测限制，前端实际Prettier已通过；消息的50条分页为原协议容量，不是新增业务阈值。
