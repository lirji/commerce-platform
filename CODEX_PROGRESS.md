# Codex Progress

## 任务目标

基于前端评审先处理 B 端经营台问题，C 端暂不改动。

## 当前状态

B 端本轮修复已完成并通过定向验证。根目录旧摘要描述的会员端视觉任务已经过时；该任务的交付记录以 `docs/PROGRESS_STATE.json` 为准。

## 已完成

- 商品经营：切换 SKU 页、重新筛选或刷新时清除旧批量选择，避免把已不在当前列表中的 SKU 带入批量计划。
- 商品经营：SKU 和 SPU 编辑表单随记录版本更新，避免再次打开时显示旧值。
- 营销旅程、营销活动/规则、运营页面：接入后端已有的 `after` 游标翻页，超过默认 50 条也可继续访问。
- 运营页面：切换草稿或从编辑转为新建时重置表单，避免沿用上一草稿。
- 新增 `frontend/tests/b-console-regression.spec.ts`，覆盖批量选择、版本回显、旅程/规则/运营页面翻页和新建表单清空。

## 验证

- `npm run build --prefix frontend`：通过。
- `COMMERCE_UI_URL=http://127.0.0.1:8621 COMMERCE_EVIDENCE_DIR=../.local/b-console-evidence npm run e2e --prefix frontend -- b-console-regression.spec.ts`：3 个用例通过，使用隔离的接口桩。
- `git diff --check`：通过。

## 未完成

- C 端评审问题按用户要求暂缓；本轮没有改动 C 端代码。
- 仓库已有的完整浏览器回归未在本轮重跑；历史失败记录见 `docs/evidence/phase4-runtime-recovery/15-regression.md`。

## 下一步建议

1. 核验本轮 B 端变更的 Git 交付状态；如尚未交付，按规则正常合入并推送远程 `main`。实际状态以 Git 历史为准。
2. 待用户安排 C 端工作时，再处理报价竞态与会员钱包翻页等评审问题。

## 恢复 Prompt

请读取 `CODEX_PROGRESS.md`，检查当前 Git 状态与 B 端定向测试结果；如 B 端变更尚未交付，继续正常合入并推送远程 `main`。C 端保持暂缓，除非我另行要求。
