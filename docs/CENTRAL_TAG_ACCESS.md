# 中央员工标签权限

CE04-T1接管标签字典定义/读取，以及已知会员的标签分配/撤销和关联读取。三个独立能力为commerce.member_tag.read、define、assign，均真实TENANT_ALL，以MEMBER_TAG族独立切换。V54扩族和实际审计目标类型，不自动切换租户；旧ADMIN不能绕过CENTRAL/STOPPED，无浏览器页面交付声明（T2待实施）。

GET/POST `/v1/admin/member-tags`对应读/定义；GET `/v1/admin/member-tags/{id}/assignments`及POST `/v1/admin/member-tags/{id}/assign`中id是实际会员编号，tagId来自正文。分配先由Member Owner提供真实会员/版本，事务中路由锁、会员锁和期限检查早于旧回执；原关联版本、注销限制、冻结可审查/操作及64个活跃标签上限保持。撤销保留关联，重授增加版本。字典列表/关联列表有界游标，返回前复核scope与适用会员版本。

定义使用会员域集合许可，不制造会员事实。它的身份审计固定记录commerce_member_tag/真实tagId；分配记录commerce_member/真实memberId，HTTP不能自选类型。中央稳定主体代际进入幂等摘要，撤权后同键也不能重放；字典/关联、平台命令和身份审计同事务，审计失败不会遗留业务效果。客户与内部规则消费仍走原成长facts，不借员工引用。

回退须使用认识MEMBER_TAG族的兼容版本并置STOPPED；直接退回旧二进制无法保证该族隔离。5秒是本地准入窗口，不承诺跨库瞬时撤权，已应用迁移不可改写。

完整tag-verify.log 417项412PASS/5既有skip；最终CentralTagMySqlTest5项另跑PASS，含后补的64上限/名额释放与真实MySQL故障回滚。真实中央演练rehearsal-c1893723fb4a共182PASS，定义/分配不隐含读、关联版本/撤销/重授、四条实际目标审计、撤权/外租户/停机503及旧模块后台恢复回归通过。SDK来源固定auth6bfaae7，两仓hygiene无阻断但Java formatter/静态分析未配置。原商城8602未切换，自有演练进程已停止，数据保留；生产映射、Owner签字与部署另需真实输入。
