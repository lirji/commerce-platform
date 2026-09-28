# 前端视觉改版

新版 Skill 的实际工程验证覆盖经营台与现有会员商城。需求、唯一架构、切片、实现和验收分别见 [BRIEF](../../design/frontend-visual-refresh/BRIEF.md)、[架构](../../design/unified-commerce/FRONTEND_ARCHITECTURE.md)、[切片](../../design/frontend-visual-refresh/IMPLEMENTATION_SLICES.md)、[实现证据](IMPLEMENTATION_EVIDENCE.md)、[验收](TEST_RESULT.md) 和 [21 张截图复核](../../evidence/frontend-visual-refresh/REVIEW.md)。

实现提交 `0283d9c` 与整合提交 `7c3fdb3` 已正常发布到 main，保留独立 IAM 规划。[Git 交付事实](DELIVERY_RESULT.json) 和 [精确提交 CI](CI_RESULT.json) 记录了已观察的结果；[远程 verify](https://github.com/lirji/commerce-platform/actions/runs/36377172720) 全部通过。后续交付记录提交只更新文档，产品摘要保持一致。

后续 main 文档提交 `823d200` 暴露了既有低代码发布回归的时序问题；复现与等待真实发布状态的修复见 [CI_REMEDIATION](CI_REMEDIATION.md)。精确提交记录保留各次结果，不以旧成功替代新结果。

当前本机预览为 [8601](http://127.0.0.1:8601)，使用现有 8602 本地 API。登录继续使用已签发的访问凭据，凭据不写进 URL、文档或页面演示数据。源码改动未重启现有后台或修改仓库默认端口。

取图命令（需先准备可用的本地 member-suite 真实种子与凭据文件）：

```sh
COMMERCE_UI_URL=http://127.0.0.1:8601 \
COMMERCE_VISUAL_ACCESS=/path/to/private/member-suite-access.json \
node frontend/scripts/capture-visual-refresh.mjs after
```

脚本只用于当前场景复核，会浏览商品、加入购物袋和计算报价，并触发声明的加载/503 测试边界；地址空表单不会提交订单。输出位于 `docs/evidence/frontend-visual-refresh/after/`。必须实际查看输出图片，工具成功本身不能替代视觉验收。

两项新增浏览器检查可直接通过项目既有 E2E 命令执行，`COMMERCE_VISUAL_ACCESS` 可指定私密副本，未设置时沿用原 member-suite 凭据位置。既有测试与 CI 配置继续保留。
