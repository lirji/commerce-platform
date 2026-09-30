# 领取预算耗尽后的轮转游标修复

## 问题与范围

成长UI回归growth-ui-verify.log发现既有OrderExpiryLaneTest公平性失败：小租户全部完成前热租户处理20单，超过quantum10。独立可控单调时钟BudgetProbe（.local/central-inventory/budget-probe-before.log）在旧实现稳定复现：a-hot处理10，b-small领取查询耗尽本轮时间且零尝试，轮转仍推进游标；下一轮先a-hot再b-small，总计20。不是本次成长权限引入，不修改或放宽原公平性断言。

独立fix/tenant-budget-cursor分支修复共享TenantRotation：仅当访问零尝试且预算已耗尽时保留原游标，下一轮先补访问；已有尝试、空队列、正常异常隔离和项数/时间上限不变。单租户快速路径也不把零尝试耗尽预算计为已访问。新增确定性时钟测试覆盖小租户先于热租户第二个quantum获得处理。

## 验证通过

旧实现确定性探针FAIL、修复后PASS（budget-probe-after.log）已保留。完整真实MySQL与所有车道mvn -B -Pwith-ui verify通过：413项408PASS/5既有skip，growth-ui-budget-fixed-verify.log。其中原OrderExpiryLaneTest3项、新TenantRotationTest10项全部通过。hygiene无阻断，保留没有Java formatter/未配置静态分析限制。

该完整工作树还包含正在验收的成长UI，相关改动和浏览器验收不进入此独立修复提交；本次只交付共享轮转修复、确定性测试和本文。CI随后核验独立提交，无新依赖、schema或运行配置。
