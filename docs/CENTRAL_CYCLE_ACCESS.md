# 中央员工会员周期与周期权益

CE04-C1将周期管理MEMBER_CYCLE与周期权益CYCLE_BENEFIT分别接管。七能力均为完整TENANT_ALL：策略read/publish、会员周期read/evaluate、礼包read/define及会员补发grant。权限不相互附赠，旧ADMIN不能绕过CENTRAL或STOPPED；V56只扩能力族与实际礼包审计分类，不自动切换租户，已应用后不能修改历史。

策略与礼包使用集合许可；会员周期读取、考核、人工补发还核对Member实际编号/版本。读取后复核scope指纹与Owner版本，写操作的路由锁、会员锁/版本和准入期限早于旧命令回执。中央稳定身份进入幂等摘要，旧模式保留原摘要。策略/礼包/周期/权益/Outbox与命令、身份审计同事务；审计失败整笔回滚，受理的REQUESTED权益不能宣称已到账。

周期策略沿既有不可变版本、生效窗口、周期天数与等级门槛；礼包引用由真实周期策略和权益Owner校验，不能由页面传递金额或伪造历史周期。人工补发只核验当前周期，按既有来源唯一键防重，不要求周期读取权限。

MemberCycleApi新增policyForOperation/viewForOperation供已授权礼包用例和可信事件处理在MANDATORY原事务内读取内部依赖；没有新增HTTP入口。员工公共policy/read仍独立鉴权。周期事件处理不再伪造ADMIN传租户，通过实际会员锁与ACTIVE约束继续系统履约。员工撤权不取消已承诺考核事件/权益发放，迟到周期/等级信号仍按原规则忽略。

身份审计policy指向commerce_member_policy/cycle-policy-<真实版本>，礼包定义固定commerce_cycle_benefit/bindingId，考核及补发指向真实commerce_member。礼包类型仅本地审计分类，不增加中央授权资源类型。五秒仅本地准入截止，不能作为跨库瞬时撤权承诺。

C1本地验证通过；C2页面已实现、真实浏览器验证中。SDK固定auth6ded091，原商城8602未切换，无生产部署或人员Grant发布。系统内部端口不授予HTTP调用者绕过业务权限的能力。

回退须使用识别MEMBER_CYCLE/CYCLE_BENEFIT的兼容版本并将对应族置为STOPPED；回退到只检查旧ADMIN的二进制会绕过接管，不能作为安全方案。停止员工入口不撤销已接受的策略推进、考核事实和权益；策略纠正沿不可变新版本，已发权益的补偿归既有业务流程，不能靠回滚镜像或删除历史宣称恢复。本片不执行生产发布或数据补偿。


### C1最终本地DONE

第二轮rehearsal-8119c3656bbb最终284PASS，cycles_checked=true，runtime_switched/production_ready=false，无浏览器。真实中央独立政策发布无read、考核无read、礼包定义无policy.read/benefit.read、补发无cycle.read；原键重试、实际政策游标、实际Member周期与礼包、跨租户与缺失Owner、四写撤权后旧回执拒绝、两域实际中央停机503通过。五次API写效果恰五条实际策略/礼包/会员审计。员工撤权后真实事件pump完成两会员权益并保持来源去重，不产生伪员工grant审计。CATALOG进程恢复/后台撤权及所有既有域API回归完整通过。权益定义的隔离准备调用尚属CE05的原Owner API，不宣称其已迁移。

完整cycle-verify-fixed.log431项426PASS/5既有skip；最终新增状态枚举与资源类型引用只替代等值字符串比较，之后cycle-package-final.log、cycle-owner-final-tests.log6项及cycle-hygiene-final.log通过，并用最终JAR完成上述联调。36工具/237入口与auth hygiene通过。两仓cycle-source-sha256/cycle-source-final-sha256复核一致；Java formatter/静态分析未配置限制保持。首轮测试准备顺序失败和59463c8a6bd9主动中止均保留，不改判定标准。自有PG/IdP/JVM已finally停止，子网93—94和证据数据保留；原8602未切换。

C1本地DONE，Git交付中；下一C2两个独立SSO页和四动作提示，当前未实现页面。其余会员/积分及CE05—08未完成，原生产输入HOLD不变。


## 员工页面

固定SSO入口为`/operations/member-cycles?tenant_id=<中央租户>`与`/operations/member-cycle-benefits?tenant_id=<中央租户>`。前者有政策查询/发布、指定会员查询/考核；后者按已知策略版本查询礼包、定义礼包、对指定会员补发当前周期权益。没有其他域列表权限时仍可用已知合法编号完成独立操作。

四个写提示分别位于`/v1/operations/member-cycles/{publish|evaluate}-access`和`/v1/operations/member-cycle-benefits/{define|grant}-access`。提示只说明当前操作资格，实际写入仍验证当前能力、真实Owner及关联策略/权益。政策不可变追加，礼包同策略等级只能定义一次；时间输入按浏览器时区解释并转换UTC提交。

响应未知时保留原目标、请求体和幂等键，冻结输入，提供原样重试；切换标签与取消退出保留意图。409保留输入供核对，401卸载业务界面，403分别拒绝各操作，503失败关闭。周期查询仅展示最后考核快照，未考核不显示虚构周期；人工考核单独授权。补发返回REQUESTED仅显示已受理待发放，空回执明确无可补发权益。

既有GET `/admin/member-cycles/policies`与会员详情路径存在保留字冲突：页面对会员编号`policies`明确提示核对，不把政策数组当会员详情。本片保持既有URI，未迁移历史编号。窄屏表格使用容器内横向滚动，表单与结果按窄屏排列。C2没有数据库迁移或新运行组件。


### C2最终本地DONE

2026-09-30：第二轮rehearsal-27a84a8559ec最终341PASS，七阶段14条周期浏览器细分检查及所有既有员工页面回归通过。真实PKCE、无read独立四写、真实409保留后纠正、服务端成功后丢响应原键原体重试、Tab/取消退出、政策/会员/礼包查询、未考核快照、REQUESTED与GOLD无礼包空回执、撤四写保留读、外租户/401和实际中央停机503通过。API5+UI5命令恰10身份审计；UI礼包定义恰1条真实目标审计、UI补发恰1条权益，空回执无权益。撤权后有界事件推进完成两个API目标权益且全部补发身份审计仍恰3条，系统未冒充员工。

已查看本轮390政策/礼包表单、政策未知输入锁定、REQUESTED/空回执、390会员/礼包查询、未考核、撤权与停机截图，表单和结果可读、表格容器横向滚动；这是实现自审，不代表用户视觉批准。最终两仓源码摘要一致；完整432项427PASS/5skip、最终build/package/Prettier、36工具/243入口、语法及hygiene通过，无Java formatter/静态分析限制保持。首轮固定pump次数不足的失败和修复原因保留，不修改业务限额或权益/审计断言。

自有PG/IdP/JVM/Vite已finally停止，子网95—96及数据/截图私密证据保留；runtime_switched/production_ready均false，原8602未切换。C2本地DONE，Git交付中；下一CE04-PTS积分（避免与基础会员P编号冲突），随后积分商品CE04-O及CE05—08，生产输入HOLD不变。
