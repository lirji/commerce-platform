# S-VR-01 TEST_RESULT

`status=COMPLETED`，`gate=PASS`，本切片所有必需验收通过。验证针对当前不可变产品摘要 `3a72634e2bfdbf5b15cd07377373dc6bb281b4013ff2d82e506b13329e589876`；源码与截图清单已再次核对，无验证后产品变化。验证阶段没有修改产品代码或降低原断言。日期：2026-09-28 UTC（本地 2026-09-27）。

## checks

| 验收 | 方法与观察 | 结果 | 证据 |
|---|---|---|---|
| V1 总览层级/真实统计/视口 | 查看 1440、1280、390 总览；原回归将净收与真实 API 对比，30 日柱/表切换正确，窄屏菜单可开关 | PASS | after/dashboard-*.png；dashboard.spec；REVIEW |
| V2 列表、长内容、字段错误与焦点 | 查看商品/订单列表（完整 UUID）、手机宽表、中文商品说明与错误表单；自动验证内部滚动、Tab 顺序、关闭后归还触发焦点 | PASS | after/catalog-*.png、orders-desktop.png；visual-refresh.spec |
| V3 商城、资源、触达与真实报价 | 查看桌面/390 商品、缺图、详情、抽屉内部滚动、报价及地址错误；图片实际加载，分类/购物按钮边界至少 44×44；报价应付与真实 POST 响应一致 | PASS | after/shop-*.png、product-detail*.png、checkout*.png；visual-refresh.spec、points-checkout.spec |
| V4 单一主题、契约与数据 | 差异审查：公共颜色仅在 theme.ts；API/DTO/锁版本/幂等、正式契约、依赖清单不变；无页面业务 Mock | PASS | 当前源码差异、IMPLEMENTATION_EVIDENCE |
| V5 构建与行为/状态 | tsc --noEmit + vite build；最终固定源码全量 26 项 E2E，26 通过、0 失败/跳过/重试失败；覆盖商品、会员、权益、旅程、订单、支付沙箱、履约、退款及即时撤权 | PASS | 本地 build.log、browser-results.json；下表与 CI 后续独立结果 |
| V6 当前截图实际查看与来源 | 最终 21 张全部实际查看；源码 hash、route、viewport、状态和时间可追溯；截图宽度与 documentWidth 均等于目标视口；捕获 pageerror 为 0 | PASS | after/capture.json、REVIEW.md |
| FORMAT | 已安装 Prettier 对全部本轮 TS/TSX/CSS/取图脚本执行 --check | PASS | .local/frontend-visual-refresh/format.log；脚本补充 check |
| STATIC_ANALYSIS / COMPILE | strict TypeScript 类型检查及生产构建；取图脚本 node --check 且实际运行得到 21 张截图 | PASS | npm run build；capture 输出 |
| CODE_HYGIENE / REPO_CONVENTION / DIFF_HYGIENE | 公共 Code Hygiene Gate 无 blocking finding；显式差异检查 | PASS | .local/frontend-visual-refresh/hygiene-gate.json；git diff --check |
| 主题正文/状态对比 | 本文使用角色色对的相对亮度计算；正文 15.881、次文白底 5.721、主色白底 6.055；五种状态最低 5.140，均大于 4.5；焦点主色在白底/选中底分别 6.055/5.415 | PASS | .local/frontend-visual-refresh/contrast.json；不声明整站 WCAG 认证 |

## 浏览器回归执行

页面验收回归开始 `2026-09-28T04:09:04.653Z`，耗时 96.753 秒，26/26 通过，flaky=0。此前首轮也通过；该回归在 CSS 清理和地址中文错误完成后重新执行，旧结果未冒充当前页面版本。后续修正既有发布回归的真实状态等待后，再次完整执行 26 项且全部通过；当前运行时间、每项结果及测试源码摘要见 [REGRESSION_RESULT](REGRESSION_RESULT.json)，其中保留上一轮统计与报告摘要。

24 项既有断言保留，增加 2 项有实际行为的检查。既有 `b-console-regression` 的声明测试边界继续使用；其他经营/购物回归连接真实 API/MySQL。独立租户、原种子逻辑及私密凭据适配见 IMPLEMENTATION_EVIDENCE；没有把测试数据写进产品页面。预览 Chromium 的截图与 E2E 是本轮浏览器范围。

## 限制与适用边界

CI 后续发现既有低代码用例未等待异步发布完成的时序问题。已用延迟真实请求验证旧用例失败、增加“已发布”前置断言后通过，详见 [CI_REMEDIATION](CI_REMEDIATION.md) 和 [RACE_VERIFICATION](RACE_VERIFICATION.json)。页面源码和当前截图未变化，完整回归及当前提交 CI 另行记录。

整改记录：可复用取图脚本首次纳入公共门禁时，`console.log` 与闭合 stage 字符串被报告为 HF-001/HF-002。改为 CLI 结构化 stdout 与命名 stage 集合后重新执行门禁，通过且无 finding；没有改规则或豁免检查。该工具修改不改变已验证的 8 个产品文件，工具本身另做语法与实际运行验证。

- `FORMAT_TOOL_NOT_AVAILABLE`：公共发现器没有从仓库脚本/配置识别出 canonical formatter；实际安装的 Prettier 已运行且通过，报告仍原样保留这个识别限制，没有新建格式平台。
- `UNIT_TEST_NOT_EXECUTED`：项目没有前端单元测试工具/套件；未把 26 项 E2E 称为单元测试。公共门禁最终状态为 `IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS`，本轮必需的类型检查、浏览器行为与视觉验收已执行。
- 本轮不修改后端，没有重跑本地 Maven 单元/数据库迁移/容量测试，不声称生产部署、真实渠道支付或所有浏览器兼容已验证。CI 按既有管线另行执行。
- 用户方向有明确来源；当前页面没有新增的用户认可反馈。这是按记录方向完成的实现与自审，不将截图或颜色自动升级成用户批准的跨项目规则。

## 复核图片

本次验证者（Codex）通过 `view_image` 查看最终 capture.json 中全部 21 张；每张的具体观察与截图链接在 [REVIEW](../../evidence/frontend-visual-refresh/REVIEW.md)。缺图、空结果、字段错误、暂缓加载和 503 均有明确状态来源；503 是声明的测试边界，并非后台真实故障。固定弹层按 viewport 检查，并补看内部滚动后的返回/报价/下单区。

进度 Owner 已同步 `S-VR-01 → DONE`，实现已按持续授权正常发布 main。精确整合提交 `7c3fdb3bf1a516004ed8b63749c056dae1cf5f61` 的远程 verify 成功，包含后端构建/真实数据库、前端构建/依赖检查及完整浏览器验收，见 [CI_RESULT](CI_RESULT.json) 与 [运行记录](https://github.com/lirji/commerce-platform/actions/runs/36377172720)。Git 事实见 [DELIVERY_RESULT](DELIVERY_RESULT.json)；其他历史能力及 IAM 规划不扩大为本轮实施。
