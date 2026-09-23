# S8a 营销资产与审批验证

PASS：111项，0失败/错误/跳过；真实MySQL/HTTP及编译边界。新增5个数据库用例及1个内部资格三值内核用例：审批跳级/旧版本拒绝、固定人群版本、MISS/过期UNKNOWN拒绝、NOT不能绕过人群、规则发布/冻结、可信字段目录、租户隔离。

初次新增人群测试发现MyBatis不能将集合size作为普通嵌套Bean属性绑定，已改为显式成员数量参数；失败证据verify-audience-mapping-failure.log保留，修复后完整验证verify-final.log通过。未修改已执行V8迁移。

旧无policy活动保留ADMIN直接发布兼容语义；新policy活动必须审批。新规则/人群版本不原位覆盖旧成交快照；报价最多保留300秒的报价时资格。内部Literal不开放给JSON运营规则。S8b券权益/预算/叠加与S5b/S7b仍未完成。
