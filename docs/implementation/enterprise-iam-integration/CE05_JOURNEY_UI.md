# CE05-J2 / EF2 真实旅程与营销效果页面验收

状态：VALIDATION COMPLETED / PASS，本地切片 DONE；主流程负责合并和远程交付。依赖 Auth `c834d38 → ad09213 → 5e7abbf`、Commerce `4ca6acc → e900616 → d65e7e5`。Dashboard 共享闭集和 V70 是独立依赖，实际聚合与九个订单/运营页面由 CE06/07 Owner 验收。

## 实现与业务边界

五个真实 SSO 页面是 `/operations/journeys`、`journey-instances`、`journey-scans`、`marketing-effects`、`marketing-executions`。导航按各族独立能力的 any-of，写岗位无 read 仍能输入实际目标；读资格不能代替写资格。静态 SPA 仅精确 GET 放行，真实业务 API 继续服务端认证与 Owner 判权。十三个资格 GET 仅短引用，不创建长任务来源或身份审计。

定义页面提供真实完整节点、频控与生命周期配置、校验、预览和全部状态动作。正固定内容版本与状态 CAS 分开；实例页面显示固定父版本、进度 CAS、执行截止、固定历史及所有控制；扫描页面保留实际版本、游标、计数、CAS 和失败原因。新定义版本不重写旧实例或旧扫描。取消只停止未提交工作，已提交通知、权益与券保留。30 天加 60 秒是 `journey_instance.create` 有限引用上限，原 Grant 的撤销或到期仍即时阻断旧任务，业务 deadline 与授权期限分别检查。

效果页面复用四个真实读取入口，保留实际门店、UTC 半开窗口、93 天上限、稳定 seriesId、覆盖和成本口径、精确字符串金额。补齐历史投影使用原有有界批次和返回游标，不隐含 ORDER_READ，也不重放付款或权益。执行页面绑定两个实际 `marketing_execution.read` 入口，保留原执行记录写入与事件语义。

`journeyClient` 校验实际最小 DTO、目标和正固定版本；坏 2xx、网络未知或 5xx 保留原 path/body/key，冻结新意图。同键重试重新检查资格，仍沿原幂等命令。成功重试清除 dirty 和旧回执、关闭表单并显示当前回执。可选预览时间为空合法，非空必须含明确时区。所有新增详情/表单使用居中 Modal，窄屏单列和正文滚动；未保存关闭、Escape 和页面离开保护保留。

## 实际验证

不可变私密证据位于本工作树 `.local/journeys/j2-evidence-v1`，最终独占运行分区为 `.local/journeys/real-runtime/rehearsal-7d6fca629449`。凭据仅在私密 fixture，不进入本文件或 Git。

| 验收 | 真实结果与证据 |
|---|---|
| 构建与当前制品 | npm typecheck/Vite、私密 Maven `package -Pwith-ui` 通过；最终 JAR `c4ead219d8dcb1216800557ee218aea6b4a8821d5bd151c9fb4e55b733586a43`，285 后端条目、88 依赖与 Owner 基线一致；76 前端源摘要固定。最后 CSS 仅格式化，使用相同真实 IAM 构建配置的 49 个 dist 文件与已运行 JAR 逐字节一致，未把默认 IAM 配置构建冒充真实包。 |
| 实际身份与独立能力 | 独占 PostgreSQL/SpiceDB/Casdoor/MySQL；真实发布十八个闭集能力、十八个单能力岗位和有限 TENANT_ALL Grant。浏览器实际密码登录、S256/state/code_verifier，无 client_secret，回跳保留 tenant。协议二十个真实 PG/图方法与 J1 二十六项真实 MySQL 各有原始不可变归档；本 UI 片不重复计算为新增后端测试。 |
| 原有限来源与政策 | 全部自有 Auth/Commerce JVM 替换后继续原引用；二十二个真实 HTTP/SQL 检查点 PASS：撤销原 Grant、重授另一 Grant 不续原源，retry 拒绝、安全取消保留已提交效果和原源摘要；撤销发布者 Grant 后固定 SYSTEM 政策继续实际生命周期扫描。 |
| 实际原 Grant 到期 | 新独立 35 秒 Grant 到期前创建实例，实际等待剩余 32.98 秒；业务 deadline 前仍因原授权到期停止，未新增通知，retry 403，cancel 保留原 Source。未宣称实际等待 30 天授权上限。 |
| 未知与坏回执 | 真实服务器首次写入后丢弃响应，第二次真实同键响应故障注入 `{}`，第三次返回真实回执；三次 key/body 完全相同，坏回执仍冻结，成功后无 dirty 误提示。最终真实 SQL 恰一条 version=1 定义、一条 `commerce.journey.create` 原命令身份审计、resource_version=1。故障注入只覆盖响应，不替代业务成功事实。 |
| 权限与不可用 | 实际撤销 read 后隐藏记录，create-only 实际成功，rebuild-only 实际提交且无 effect.read，execution.read 独立 403；实际无效 Bearer 401 清除会话并回到企业登录。仅 SIGSTOP 已验证归属的本任务 Auth PID，真实 503 清除旧数据并禁用动作，finally SIGCONT 后恢复。 |
| 五页与视觉 | 最终 52 张当前截图全部实际打开核对，覆盖 1440/390/320 定义八种表单、固定详情、实例入组/控制/历史、扫描恢复、效果重建/四报告及执行页。弹层居中、正文滚动、无新增 Drawer、页面无水平溢出，表格在容器内滚动。另核对权限/401/503 状态图片；这些权限图片来自同一后端的前一 UI 包，最终包的成功关闭与 unknown 已由最终浏览器和 SQL 再验，不混为最终 52 图。 |
| 键盘与长表单 | 最终包 320 视口实际 Tab/焦点、Escape 未保存确认、继续编辑保留输入、正文滚动至两个底部按钮、确认放弃关闭通过。新增 `frontend/tests/central-journeys.spec.ts` 在真实隔离 fixture 上实际运行 1/1 PASS；默认无 fixture 明确 skip，不向通用环境写业务数据。 |
| 静态路由与 hygiene | 五个精确 GET 200 HTML、同路径 POST 401、匿名业务 401。新增 TS/TSX/CSS/测试 Prettier 与 git diff 检查通过；Java formatter 未配置是既有限制。 |

原脚本选错真实 DTO 列、重复文字/按钮定位以及初次环境/时区失败日志保留，不将旧 FAIL 改成 PASS。终态文件、脚本、截图、五份 MySQL XML 和源 SHA 单独归档。本片没有修改原运行目录、历史迁移、共享数据或共享 Maven 缓存。

## 集成与实际发布

本片十八个能力和五个菜单来自实际 EmployeeAccess、中央绑定、navigation、pageMap 与精确 SPA 源；实际导航的任何动作能力均可进入对应菜单，父菜单只浏览、不得级联授予。私密 `journey-owner-publication-map-current.json` 提供该映射和源摘要，交由统一 Owner 发布完整 catalog，不能拿本演练十八能力/零菜单 manifest 充当整个应用最终发布。

CE06/07 的九页共享接线已由本任务提供专属 `operations-routing-owner-v1.patch`，其 Owner 在另一隔离树应用验证；本片不混入尚未交付的外域页面。主流程最终将五页与九页按同等精确 GET/any-of 合并，执行受影响回归，再统一合并/push。完整应用能力目录、岗位快照和实际菜单发布仍属于统一收尾，不从本片 PASS 推断全量已经发布。
