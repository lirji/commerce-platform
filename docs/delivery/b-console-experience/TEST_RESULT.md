# B端验收记录

日期2026-09-30；基线654d903；任务分支refactor/b-console-experience。当前产品源码和测试SHA256：`344ab3a967cf6e7270e72084b2db0c86a1540ee4bb3489346c228b230865fc2e`。算法为排序后的frontend/src TS/TSX/CSS、frontend/tests TS及两个变更Java文件，以路径和内容、NUL分隔累计SHA256；完整清单在忽略的 `.local/b-console-experience/source-fingerprint.json`。

## 可观察验收

| 切片 | 验收 | 方法/当前结果 | 原始证据（项目根相对路径） |
|---|---|---|---|
| B01 | 刷新身份/查询恢复，退出清除，401失效，503可重试；服务端重新读取 | PASS，浏览器真实操作及请求断言；商品可见筛选、订单深链接/焦点/返回、中央门店页签 | `.local/b-console-experience/behaviors-last.log`、`final-behaviors/browser-results.json`、`central-browser-rerun.log` |
| B02 | 平台独立登录/刷新，租户403、匿名401，不预取租户目录 | PASS，新PlatformRuntimeAuthorizationTest＋真实MySQL HTTP＋浏览器请求断言和8612实际UI | `backend-verify-rerun.log`、`api-smoke-result.json`、`runtime-capture.json` |
| B03 | 恢复证据、显式选择、逐项结果、原键重试、审计；试运行绑定范围、真实任务/版本控制 | PASS，交互夹具模拟未知503；实际API验证恢复REJECTED原键相等、空范围创建RUNNING→CANCELLED且版本0→1 | `behaviors-last.log`、`api-smoke.log`、`api-smoke-result.json`、`runtime-last.log` |
| B04 | 管理/中央页族、桌面/手机、表单/详情/菜单/错误/未保存/分页完整体验 | PASS，29个ADMIN入口1440＋6个390，商品/恢复/重放另验1280；17个中央入口1440＋中央403/菜单390；平台1440/390、关联弹层实际查看 | `capture.json`、`screenshots/`、`central-validation/central-screenshots/`、`runtime-screenshots/`、`real-business/` |
| B04 | 真实商品图片/缺图、发布/撤权、成长/标签、人群刷新、营销成交退款、旅程入组 | PASS，六项真实经营流程；另商品类目/模板/图片/条码与经营汇总共两项通过 | `operations-last.log`、`real-business-rerun.log`（前两项PASS，旧页签假设首次失败保留）、`real-business/` |
| B05 | 构建、格式、类型、数据集成、静态差异、产物匹配 | PASS，见下方；Git/远程CI由DELIVERY_RESULT单独记录，未执行前不声称发布 | `build-release-final.log`、`package-last.log`、`format-check.log`、`backend-verify-rerun.log` |

表内省略的证据前缀均为 `.local/b-console-experience/`。服务端470个测试案例、失败0/错误0/显式性能实验跳过5（三个BackgroundRuntimeBenchmark、EventDispatchProfile、EventSchedulingBenchmark）；实际执行465。前端本轮明确PASS为14交互＋1中央页族＋6经营＋2商品/总览=23个测试案例，中央单个案例遍历17入口。没有将页面截图数量算成测试数量。

完整后端命令：加载原runtime.env与已有owned-rules.env后`mvn -B verify`，测试数据库是既有隔离MySQL43308。后端源码在该PASS后未再修改。前端`npm run build --prefix frontend`含TypeScript检查；无新增依赖。with-ui `mvn -B -Pwith-ui package -DskipTests`仅包装已验证版本，不冒充额外测试；逐文件核对jar静态资源与当前dist字节相同。修改前端文件Prettier检查PASS，Java沿既有tabs/中文原因注释；`git diff --check` PASS。未配置ESLint/前端单测，静态检查证据为TypeScript及本轮只读审查，不虚构lint/单测。

## 视觉与关联交互

实际打开查看全部管理入口和中央入口联系图，并查看总览、SKU/商品身份、营销详情、商品字段校验、恢复/重放窄屏、中央拒绝/菜单、真实运行健康与11消费者安全矩阵的原尺寸图片。最后一轮更改语义等价的mask属性及控制Form生命周期后，重新截取并核对对应矩阵/表单/页面族；无页面横向溢出及JavaScript pageerror，最终console警告0。捕获到的Ant弃用/未挂载警告作为实际缺陷修复；最终console记录以capture.json为准，不把早期日志覆盖成无警告。

原经营回归产生的会员历史、商品价格历史、成长/标签、人群刷新、营销预览与效果、旅程配置截图也作为关联业务结构证据逐张实际查看。按钮操作、关闭/返回、未保存保护、错误、分页/焦点证据来自Playwright，不由截图猜测。颜色对比按主题实际值计算：主文字15.88、次文字5.72、蓝色6.05对白底；状态文字最低5.83。关闭动效沿用统一theme.motion=false；无外部字体/新图片依赖。

## 失败与修订记录

- 首次完整后端验证：历史CouponDeliveryTest受200ms预算影响，期望20实际18；未改测试/业务，同版完整重跑PASS。保留backend-verify.log与backend-verify-rerun.log。
- 新UI第一轮严格选择器匹配隐藏表行；修正到实际行复选框。复验暴露创建/控制重复reason标识，按实际Form命名修复；审计选择器限定活动tabpanel，原业务断言保留。
- 中央测试最初按英文Close找中文按钮，改为实际抽屉关闭按钮；17入口、页签、错误、窄屏全通过。
- 实际业务首次准备缺少member-suite种子，补独立种子；随后旧成长测试默认页签假设失败，导致其成长未写入和两项下游断言失败。明确选账本页签、保留全部业务断言，以新隔离tenant重跑六项全部PASS。
- 联调脚本首次硬编码数据库用户错误，改为隔离环境已声明用户；截图脚本首次使用简写tab名，按真实“消费者安全矩阵”修复。无服务端或页面断言降级。
- 每次产品行为变更后重跑相关构建/交互/真实业务；最终版本/源码摘要与交付逐项核对。

## 环境与边界

8602旧容器及原共享基础设施未重启、未升级；8611源码预览、8613显式中央UI预览、8612新后端自有进程。业务种子使用脚本--fresh、当前任务私密目录，新增独立测试tenant，不覆盖其他任务凭据；8612工作器关闭，空范围重放测试任务已取消。无建表、清库或生产部署。凭据在忽略文件且权限600，不进入日志/文档/截图。

中央壳层的验证通过显式OIDC sessionStorage及公开DTO HTTP边界，只证明UI路径与禁止旧读取，不证明本轮重新交换真实OIDC或新增中央授权。既有中央服务端MySQL契约测试在完整后端中通过。此前中央员工迁移待办不会因此标记完成。

保留限制：既有UI库chunk1,038kB构建警告（gzip332kB）；未单独声明/测量生产性能、跨浏览器认证或全站无障碍合规；五个显式性能实验未运行。Awwwards/Webby/FWA外部奖项或评委认可UNVERIFIED。本轮可观察功能与视觉检查PASS，`implementationStatus=IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS`，无本范围产品阻断。
