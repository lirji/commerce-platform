# Phase 8 基线

## 权威起点

Phase 7 已完成正常 Git 交付。两笔代码 `11c7f37`、`a6b3b8d`，验收归档 `40376b3`，交付结果补记 `aa8bef1`；当前本地 main 与真实远程引用均为 `aa8bef17343c9722b3d2f19a4f9d9a006d694ae1`。创建 `feat/phase8-marketing-journey` 时工作树干净。未强推、未部署。

输入方案：`/Users/liruijun/Downloads/phase-8-marketing-journey-workflow-orchestration.md`；SHA-256 `4e2958bfafae16d4fc02de6de36dc0bdc6b82f92ff380e335be5c2cf1fbb4aa2`。既有保证以 Phase 7 报告及 05 滚动兼容、12 多实例、15 回归证据为起点；同时读取 Phase 3/4/6 报告，不重启全项目扫描。

## 本轮可执行证据

创建新分支前重新执行 `./scripts/build.sh`（clean）：

| 范围 | Tests | Failures | Errors | Skips |
|---|---:|---:|---:|---:|
| commerce-app | 279 | 0 | 0 | 5 |
| architecture-tests | 3 | 0 | 0 | 0 |
| marketing | 27 | 0 | 0 | 0 |
| order | 45 | 0 | 0 | 0 |
| shared-kernel | 3 | 0 | 0 | 0 |

UI 进入最终 jar，未发现 benchmark 辅助 class。最终 jar 受影响浏览器 2/2（18.1 s）。原 Phase 5 全浏览器历史边界未被本次两项覆盖。日志留在忽略目录 `.local/phase7-git-delivery-build.log` 与 `.local/phase7-git-delivery-browser.log`，无秘密进入证据。

这次基线复用 Git 交付前的新鲜构建：交付过程中仅补文档，没有改变被验证的产品代码。MySQL 隔离库 `commerce_test_20260923` 为 V43；未操作生产或共享业务库。

## 工程系统

Codex Adapter entry resolve 实测 READY；Engineering Skill System 2.0.0，Adapter 1.1.0，阻塞 drift 0。这里只认证本地 Adapter，不声称厂商会话恢复已验证。后续按 Router → 架构/契约 → 一致性门禁 → 切片 → 实现/验证 → 进度推进。
