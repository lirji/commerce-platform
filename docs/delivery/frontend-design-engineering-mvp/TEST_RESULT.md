# 前端设计工程隔离候选与商品经营页试点验收

2026-11-26 · 历史隔离验收记录 · 共享核心正式接入待评审

**建议暂缓正式纳入共享核心。** B 组改善了窄屏的筛选密度和条码入口辨识度，桌面运营主路径没有获得一致优势。A 在两个桌面视口的查询入口、重置可达性和列宽分布更利于扫读。本次只涉及一个业务场景，尚不足以证明普遍品质提升。正式运行同时受共享核心健康、工作流产物和可信验证来源约束。

## 1. 隔离范围与可复现结果

- 共同基线为 Commerce `107a8c0a81e07073ced12dc6a3282c2908de1e4a`。既有镜像 `da04c1e` 与冻结基线的前后端源码无差异。隔离 MySQL 执行 73 条原有迁移至 V74，两组共用原项目 API 种子及同一拍摄租户，数据摘要一致。原有 E2E 写步骤在每组自己的新租户执行。 [实验协议](PROTOCOL.md) · [测试指标](../../evidence/frontend-design-engineering-mvp/ab-experiment.json)
- A、B 分别使用两个独立新 Codex CLI 会话，均为 `gpt-6.2-sol` / `medium`。任务原文、模型设置、可写文件、浏览器、数据和视口一致。两组第二轮仅收到自身首轮图。A 读取安装的页面指引，B 使用隔离候选增强的页面指引。候选运行时单独进行路由和门禁试验，两套产品实现由有界交互 CLI 执行。 [业务请求](REQUEST.md) · [成本与取证](../../evidence/frontend-design-engineering-mvp/ab-experiment.json)
- A 本地提交 `909edb9b1fa113ba608491e583159425404d5382`，B 本地提交 `81d8c4f1dd4d5ad48ea1e4bbc927920434b915af`。两组只改变 `ProductOperations.tsx` 和局部 CSS Module，保留依赖、后端、接口协议和权限实现。该组件也服务中央入口，中央真实 SSO 和受影响面仍需核对。 [A 补丁](../../evidence/frontend-design-engineering-mvp/a-implementation.patch) · [B 补丁](../../evidence/frontend-design-engineering-mvp/b-implementation.patch) · [Git 回执](../../evidence/frontend-design-engineering-mvp/git-delivery.json)
- 治理候选位于共享源之外，变更 20 个文件。它复用现有 Skill ID、意图编译、能力计划、独立验证和 Quality Runtime。 [候选补丁摘要与治理回归](../../evidence/frontend-design-engineering-mvp/governance-summary.json)

## 2. 实际页面结果

| 维度 | A 现有能力 | B 隔离候选 |
| --- | --- | --- |
| 桌面查找与商业表达 | 页首说明直接服务查找；筛选主按钮与整组重置明显，售价、状态和条码靠前。 | 「规格列表」和筛选区分明，条码资料位置稳定、字重醒目。实心创建按钮的视觉权重高于查询。 |
| 1920×1080 | 名称、状态、售价和条码距离相对集中，列宽均衡。 | 短销售名称与状态、售价距离较远。DOM 实测名称列 586px，A 为 244px，次要 SKU 仍有换行。 |
| 390×844 | 首轮售价在横滚区域外。复验后名称、状态和完整售价同时可见，完整筛选及重置始终显示。 | 首轮售价被裁。复验后名称、状态和完整售价同时可见，并将类目和价格收入「更多条件」，表头约比 A 提前 20px，「更多条件」实际可展开和收起。两组条码动作均需横滚。 |
| 详情与异常 | 稳定复拍完整呈现条码弹窗；区分无匹配与受控 503，保留查询输入，显示重试。 | 条码弹窗与异常处理呈现一致。无匹配时可以清除输入，未提供整组重置。 |
| 功能与一致性 | 复用项目组件和 token，构建、格式、原商品 E2E 通过。 | 同样通过，真实筛选和返回行为保持。 |

独立审阅先使用匿名 X/Y 图集。审核实际查看每组 6 张，再在复拍后实看两组 4 张桌面详情图。自动化用时只作为运行证据，缺代表岗位样本，不能用毫秒差推断人员效率。 [截图索引](SCREENSHOTS.md) · [盲审记录](../../evidence/frontend-design-engineering-mvp/visual-review.md)

## 3. 视觉反馈与外部方法

| 首轮问题 | 修正和复验 |
| --- | --- |
| 窄屏核心售价不完整 | A 将名称、状态、售价移到首组并收紧间距。B 折叠低频条件并调整窄屏列宽。两组同尺寸复验均显示完整售价。 |
| 桌面编号折行、行操作过密 | 两组调整列宽与动作间距。A 强调筛选，B 强调创建和条码资料。部分长编号仍可换行，1920 的 B 列分布需要改进。 |
| A 一张详情图仅捕获遮罩 | 保留原图，增加条码内容可见、动画完成和尺寸稳定条件，并等量复拍，四张稳定桌面详情图均完整。 |
| 首次 Runner 登录后未进入商品页 | 保留失败诊断；按项目原导航方法，等待真实登录结束后再选择功能。 |
| 隔离环境准备阻塞 | Docker 默认地址池问题以专用子网处理。MySQL V29 因默认排序规则失败，复用 CI 的 `utf8mb4_bin` 后迁移通过。内部网络未建立宿主端口映射，后以仅环回公开的 API 接入已迁移的独占库。 |

固定版本的 Anthropic Frontend Design 和 Vercel Web Design Guidelines 已用于 B 首轮实图的额外审查。Anthropic 的任务导向文案、字阶和分组帮助定位窄屏筛选成本与条码操作间距。Vercel 的溢出规则识别 390 下价格缺字，并指出静态图不能证明焦点行为。外部审查与两组第二轮相互独立。结果能证明方法可产生可复核发现，单次实验不足以判定外部 Skill 单项贡献。浏览器由项目现有 Playwright 驱动本机 Chrome。 [外部实测](../../evidence/frontend-design-engineering-mvp/external-review.md) · [Anthropic 固定版本](https://raw.githubusercontent.com/anthropics/skills/e4bb5d960d87126ff3b3a8af1a104b7db67560ba/skills/frontend-design/SKILL.md) · [Vercel 固定规则](https://github.com/vercel-labs/web-interface-guidelines/blob/434b7f91364665f2f733b310ec54809bf8f37937/command.md)

## 4. 自动触发与视觉门禁

| 档位 | 候选做法 | 实际验证 |
| --- | --- | --- |
| FAST | 局部可见样式，复用页面 Skill，证据以当前受影响面为限。 | Compiler、能力计划、现有 Planner 的 FAST 投影和授权否决通过。 |
| STANDARD | 新页和主要视觉交互改造，既有页面 Skill 实施，既有验证角色独立验收。 | 两类请求选中正确组合，验证节点无产品写权限。真实 A/B 图各有 8 项进入候选 Quality 视觉检查，过期版本拒绝。 |
| FULL | 全局主题、设计体系和共享组件交给既有前端架构入口；用实际差异复核升级。 | 规划、路由和升级均有夹具正反例。完整跨页实机验证待后续。 |
| 无可见影响 | 前端 API 类型、请求重试、已批准旧切片或纯后端留在原路径。 | 用例未增加新视觉任务；既有意图未知时保留系统原结果。 |

专项 73 项断言涵盖截断 PNG、状态、角色、版本、视口、产物摘要、缺截图、路径越界、伪造审批标识、自报 PASS、验证者权限和缺失交接。实图门禁实验绑定各自代码、渲染资产、固定浏览器和当前范围，可信输入从试验控制者管理的项目树外目录提供。候选默认失去独立证据来源时失败。实际商业工作树能编译到 STANDARD，但缺新鲜登记的 CONTRACTS 与 IMPLEMENTATION_SLICES，原 Preflight 正确阻断。真实 Kernel 无任务写入或正式授权交付。 [专项回归](../../evidence/frontend-design-engineering-mvp/candidate-regression.json) · [真实证据门禁](../../evidence/frontend-design-engineering-mvp/real-browser-quality.json) · [实际 Preflight](../../evidence/frontend-design-engineering-mvp/live-preflight.json)

两组 TypeScript、Vite、Prettier、Git diff 检查通过，原 `catalog-merchandising.spec.ts` 各 1/1 通过。基线 Workflow Synthesis 缺 frontend-design 契约，候选补齐后通过。授权边界仍有原 2 项失败，Registry Lint 仍有原 42 项错误，已保留对照。 [平台回归对照](../../evidence/frontend-design-engineering-mvp/governance-summary.json) · [A/B 原业务回归统计](../../evidence/frontend-design-engineering-mvp/ab-experiment.json)

## 5. 执行成本

| 两轮 CLI 合计 | A | B |
| --- | ---: | ---: |
| 执行时间 | 463 秒 | 420 秒 |
| 总输入 token | 2,744,612 | 2,096,267 |
| 缓存输入 | 2,603,008 | 1,961,472 |
| 非缓存输入 | 141,604 | 134,795 |
| 输出 token | 17,744 | 16,839 |
| 其中推理输出 | 5,696 | 5,251 |

B 本次少 43 秒，非缓存输入少约 4.8%，输出少约 5.1%。CLI 对每次模型交互累加，输入和缓存包含重复上下文。两组共用的环境准备、构建复验、独立核查和候选实现未计入两轮 CLI。未估计账单或将单次观测视为普遍节省。 [成本证据](../../evidence/frontend-design-engineering-mvp/ab-experiment.json)

## 6. 漂移影响、设计缺口与审批节点

- 共享核心仍为 `SHARED_CORE_NOT_READY`。frontend-design 内容漂移影响其发布来源；两个目录内的 .DS_Store 造成安装完整性差异；scan-input 不一致影响安装扫描权威绑定。入场和出场 Guard 快照一致。正式发布前须按现有维护流程审议差异归属、批准精确改动，校验回归、版本登记、可信 Driver 和回滚。 [共享核心状态与快照一致性](../../evidence/frontend-design-engineering-mvp/governance-summary.json)
- 设计缺口来自验收只证明「可见、可用」，尚未表达「最高频任务优先」。B 的销售名称列没有明确的桌面 width，其余列使用固定宽度，浏览器在 1920 将多余空间分配给名称列。B 的新建按钮延续为实心，说明指引虽提示区分查询和变更，未把本试点的查询优先级落实为验收。下一轮需先登记运营主任务及首屏主次，核对宽屏关键列距离，并在代表岗位、更多 SKU 密度和多个场景中复验。 [B 实现](../../evidence/frontend-design-engineering-mvp/b-implementation.patch) · [盲审](../../evidence/frontend-design-engineering-mvp/visual-review.md)
- 工程缺口包括生产级的独立证据写入边界、合法的业务产物与 Driver 前置、纯页面只读独立调度、真实中央 SSO 及跨页消费者验证。隔离候选使用外部验证者收据路径，正式运行端仍缺可信采集源。旧路径、审批或安装指纹未替换。

保留两组本地样本、候选补丁及实际回归证据，停在正式接入审批节点。

此归档对应历史试点基线。当前主线产品演进见[运营与商业化实施进度](../commerce-operations-product/PROGRESS_STATE.md)。A/B 补丁用于复现实验。共享核心原始补丁与本机治理日志保存在项目忽略的 `.local/frontend-design-engineering-mvp/`。
