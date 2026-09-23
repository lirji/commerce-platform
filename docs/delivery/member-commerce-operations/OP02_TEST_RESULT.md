# OP02 验证记录

- `./scripts/verify.sh` PASS，148测试；真实MySQL应用V17。StoreAccessTest验证门店范围、商家范围包含新增店铺、跨租户隔离、转授拒绝、旧admin路由拒绝、撤销立即失效、幂等重放、并发版本拒绝和无有效身份拒绝。
- `npm run build` PASS；`git diff --check` PASS。
- 浏览器实际操作待OP08；商品写操作的范围回归待OP03接入。
- OPERATOR只在显式operations路由和身份自查路由放行，其他路由默认拒绝；没有将其模拟为ADMIN。
