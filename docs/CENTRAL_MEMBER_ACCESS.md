# 中央会员员工接入

## 已实现范围

CE04-P0/P1：基础会员四能力member.read/create/profile.update/status.update，真实TENANT_ALL；OA员工显式映射本地OPERATOR，客户本人账号仍按商城绑定。V52新增MEMBER_PROFILE独立族和commerce_member身份审计类型；旧ADMIN不能绕过已接管族，配置关闭或STOPPED不能退回旧权威。G/T/B/C/P成长/标签/行为/周期/积分尚未接管，不从基础会员能力隐式继承。

MemberService集合读取在SQL前/后检查许可；已有会员由本域读出真实ID/version追加对象判权。修改事务先锁权限路由、再锁会员并比较事实版本，早于旧幂等回执；命令摘要绑定稳定主体/成员代际，审计与业务同事务。创建注册事件继续走原Outbox。注销终态、expectedVersion、原因和客户/内部交易语义保持。5秒是本地准入窗口，不代表跨服务原子撤销；旧二进制不认识新族，回退必须使用认识该族的版本和STOPPED。

## CE04-P2员工页面

固定`/operations/members?tenant_id=<UUID>`，沿用现有SSO。页面仅向会员基础API携中央凭据，不写旧全局token。会员档案50条游标、变更记录抽屉及新建/资料/状态三个独立工作区；GET `/v1/operations/members/{create|profile|status}-access`分别检查对应能力，返回`{allowed:true}`仅作为提示。提交时仍判权，读取权限不成为写入的隐含前提；独立修改岗位使用已知会员编号/版本。

401移除业务页；403明确拒绝；409保留输入提示核对版本；503/断网不宣称成功，锁定原输入/幂等键原样重试，之后撤权也不丢弃未知意图。切页签保留表单，退出与浏览器离开提示未保存/未确认操作。注销保留不可恢复说明，沿用原流程，不新增OA逐笔审批。

## 验证与环境

P1完整406项401PASS/5skip、真实中央隔离114PASS已交付：commerce1bc81f2，auth2cb8c11；两仓CI36669783915/36669785165 SUCCESS。P2完整407项402PASS/5skip、前端build/Prettier/package已通过；最终真实隔离rehearsal-aa0b96471601共137PASS，会员12条浏览器检查和目录/库存/CATALOG回归通过；1440/390及创建/修改/历史/故障截图已实际查看。P2本地DONE，Git交付另记。真实数据库/身份/业务写均为新建本地隔离环境，原8602不切换。更完整证据在auth-platform/docs/implementation/oa-auth/commerce-readiness/CE04_MEMBER.md；生产映射/Owner/容量与部署仍待对应输入。
