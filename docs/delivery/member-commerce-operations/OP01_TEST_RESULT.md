# OP01 验证记录

- 基线 `./scripts/verify.sh`：PASS，原有全套测试通过。
- 当前 `./scripts/verify.sh`：PASS，147测试（原145＋会员经营2）。真实MySQL V16迁移成功；覆盖资料/冻结/恢复/注销、审计、旧版本、重放、并发覆盖、越权和冻结报价拒绝。
- `npm ci --ignore-scripts && npm run build`：PASS，TypeScript与Vite通过。打包体积警告延续现有单bundle，功能无构建失败。
- `git diff --check`：PASS。
- 浏览器交互：UNVERIFIED，纳入OP08新增业务全链路验证；本阶段仅声明API/持久化及UI构建已验证。
- 初次尝试Maven指定单类被父pom的failIfNoTests门禁拒绝；未改门禁，改跑完整verify通过。

代码范围：member、MemberOperationsController、V16、MemberActions/AdminData、MemberOperationsTest；提交SHA由Git历史关联本记录。
