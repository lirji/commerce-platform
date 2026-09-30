# 中央员工标签权限

CE04-T1接管标签字典定义/读取，以及已知会员的标签分配/撤销和关联读取。三个独立能力为commerce.member_tag.read、define、assign，均真实TENANT_ALL，以MEMBER_TAG族独立切换。V54扩族和实际审计目标类型，不自动切换租户；旧ADMIN不能绕过CENTRAL/STOPPED，标签员工页见下文T2验证状态。

GET/POST `/v1/admin/member-tags`对应读/定义；GET `/v1/admin/member-tags/{id}/assignments`及POST `/v1/admin/member-tags/{id}/assign`中id是实际会员编号，tagId来自正文。分配先由Member Owner提供真实会员/版本，事务中路由锁、会员锁和期限检查早于旧回执；原关联版本、注销限制、冻结可审查/操作及64个活跃标签上限保持。撤销保留关联，重授增加版本。字典列表/关联列表有界游标，返回前复核scope与适用会员版本。

定义使用会员域集合许可，不制造会员事实。它的身份审计固定记录commerce_member_tag/真实tagId；分配记录commerce_member/真实memberId，HTTP不能自选类型。中央稳定主体代际进入幂等摘要，撤权后同键也不能重放；字典/关联、平台命令和身份审计同事务，审计失败不会遗留业务效果。客户与内部规则消费仍走原成长facts，不借员工引用。

回退须使用认识MEMBER_TAG族的兼容版本并置STOPPED；直接退回旧二进制无法保证该族隔离。5秒是本地准入窗口，不承诺跨库瞬时撤权，已应用迁移不可改写。

完整tag-verify.log 417项412PASS/5既有skip；最终CentralTagMySqlTest5项另跑PASS，含后补的64上限/名额释放与真实MySQL故障回滚。真实中央演练rehearsal-c1893723fb4a共182PASS，定义/分配不隐含读、关联版本/撤销/重授、四条实际目标审计、撤权/外租户/停机503及旧模块后台恢复回归通过。SDK来源固定auth6bfaae7，两仓hygiene无阻断但Java formatter/静态分析未配置。原商城8602未切换，自有演练进程已停止，数据保留；生产映射、Owner签字与部署另需真实输入。


## 员工标签页面

固定 `/operations/member-tags?tenant_id=<中央组织UUID>`，沿现有企业SSO。字典与会员关联分别有界分页，会员使用已知编号；定义/分配两个独立工作区，不隐含标签读取。提示接口 `/v1/operations/member-tags/{define|assign}-access` 只提示资格，提交仍核对实际Member Owner。

定义填写标签编号/名称；分配或撤销填写会员/标签编号、当前关联版本和原因，首次关联版本0，后续用真实版本。撤销保留历史关联并增加版本。未知结果冻结原输入和幂等键，只能原样重试；409保留输入，401卸载业务，403分别拒绝，503显示依赖故障。切Tab保留表单，退出/离开保护未保存及未知意图。

T2完整回归419项414PASS/5既有skip，前端最终build/package与hygiene通过；234入口/36项工具PASS。真实浏览器演练fdda68438ef6验证中，完成证据另记。未修改V54或新增依赖。


### T2最终本地DONE

rehearsal-fdda68438ef6最终221PASS；标签五阶段10条浏览器细分检查，成长11/基础会员12/目录11/库存9/CATALOG9共享回归全部通过。真实PKCE定义标签无read/assign、独立分配无read、真实提交响应丢失后原输入/相同key/body重试、取消退出和Tab保留；实际字典/会员关联读取、错误版本409保留后纠正、撤销保留关联且版本2、独立撤权保留read/define、外租户/401及真实中央停机503通过。SQL/API证实UI三次效果与API四次效果恰7条身份审计，无重复字典/分配。

已实际查看本次define-form、390-define-form、assign成功、unknown、dictionary、conflict、390-revoke-form、revoked-association、390-assignments、outage截图：层级/文字和表单可用，窄屏表格仅容器横向滚动；初始desktop定义截图捕获表单校验消退中间态，后续390已清除，实际提交通过。截图是自审，不声称用户视觉批准。自有PG/IdP/JVM/Vite已finally停止，子网87/数据/私密证据保留；原8602未切换。

最终419项414PASS/5skip、36工具/234入口、前端build/package及两仓hygiene/diff通过；Java formatter/静态分析未配置限制保持。最后产品变动仅操作枚举常量，发生于标签浏览器阶段前并已重新build/package；后端与完整回归相同。T2本地DONE，Git交付中；下一CE04-B行为，技术草案已盘点，其他会员与CE05—08仍未完成。
