# 中央员工会员行为权限

CE04-B1沿用行为资料/事件查询、偏好修改与历史成交补建API，以MEMBER_BEHAVIOR独立接管。commerce.member_behavior.read/update/rebuild分别授权，范围为真实完整TENANT_ALL；旧ADMIN不能绕过CENTRAL/STOPPED。V55扩族与实际批次审计分类，不自动迁移租户，已执行后不改历史。

读取及偏好修改由Member Owner提供实际会员编号/版本，读取前后复核许可和会员版本；偏好写入的路由锁、会员锁及期限复核早于旧幂等回执，原生日MM-DD、旅程开关、ACTIVE状态和偏好版本规则保持。中央稳定身份代际进入幂等摘要，偏好、命令与身份审计同事务。客户本人接口、商品交互额度和内部行为事实消费保留原规则。

POST `/v1/admin/member-behavior/rebuild`保持after/limit（1至50）输入与next/scanned/done响应，逐批显式提交。重建不附赠行为读取/修改或订单查询权限。应用服务取得独立集合许可后，通过订单Owner内部端口读取同租户真实orderId/createdAt，再调用会员既有投影端口；不借旧ADMIN，不把订单商业或地址资料传到页面。行为投影仍从会员成长权威来源锁后读取净额，不能采信客户端金额。

重建身份审计实际类型为commerce_member_behavior_batch，resourceId为持久命令key，联合tenant/actor/operation/key可对应platform_command；空批次也有真实回执。它是本地审计分类，不是新增中央授权资源类型或虚构会员。投影、命令和归属同事务，批次末尾本地许可到期也整批回滚。未知结果只能原键/原输入重试，撤权或STOPPED后旧回执仍拒绝。

回退需支持MEMBER_BEHAVIOR族并设置STOPPED，旧二进制不能保证该族隔离。5秒是本地准入期限，不承诺跨库瞬时撤销。原商城8602保持不变；本片无生产部署、真实OA映射、人员Grant发布。B2员工页面已完成，见下方验证。

SDK来源固定authf7fa425，compile与SDK安装通过；最终验证如下。


### B1最终本地DONE

第三轮rehearsal-31b246afdc39共221PASS，behavior_checked=true、runtime_switched=false、production_ready=false，无浏览器。实际中央独立update无read/rebuild、生日02-29/非法生日400/版本409、客户本人读取与真实商品浏览、重建无read/每批有界/连续游标和空回执、实际统计净额12.00/事件游标、外租户/缺失Owner、修改及三批重建恰4条实际目标审计、两个写权限撤销后同键拒绝且读取保留、真实中央停机503通过。CATALOG任务跨进程恢复/导入撤权/旧快照不可复活/STOPPED安全回退及旧各域回归完整通过。历史订单/成长数据为明确隔离种子，不冒充支付履约实测；既有6项行为测试覆盖真实业务链。

完整424项419PASS/5既有skip、SDK固定来源/compile、36工具/234入口契约、两仓hygiene/diff无阻断；Java formatter/静态分析未配置限制保持。behavior-source-sha256.json记录并复核两仓验证源码摘要一致，产品语义自回归未改，仅Controller中文注释。第一次授权接口与第二次脚本变量失败均保留，最终修复未改业务或放宽断言。自有PG/IdP/JVM finally已停，子网88—90和数据/私密日志保留。原商城8602未切换。

B1本地DONE，Git交付中；下一B2已正式细化三个工作区/两独立提示/真实浏览器，页面尚未实施。其余会员和CE05—08仍未完成，原生产输入HOLD不变。


固定SSO入口`/operations/member-behavior?tenant_id=<UUID>`，查询、修改偏好、历史成交补建三个独立工作区；修改与补建通过本域独立提示取得操作资格，实际提交重新鉴权。使用已知会员编号与偏好版本；补建每批1—50条并手动延续实际游标。凭据仅本域请求使用，不写入旧控制台全局上下文。


### B2最终本地DONE

真实rehearsal-1d02cf3bcffd最终267PASS，行为五阶段11条浏览器细分检查，标签10/成长11/基础会员12/目录11/库存9/CATALOG9回归通过。真实PKCE、独立修改无read/rebuild、闰日校验、独立补建无read、两写服务端成功后丢响应原键/原输入重试、取消退出/Tab保留、实际统计及事件、409保留后纠正、未知无成交、撤写保留读、外租户/401和真实中央停机503通过。API/SQL证实偏好UI版本2，两次修改恰两条偏好审计；四次实际重建批次、三次偏好修改共7条身份审计，无重试重复效果。

已查看本次390-update-form、unknown、rebuild、390-detail、conflict、updated-detail、outage、revoked（401状态）和390-rebuild-form实际截图，表单与结果可读，窄屏表格仅容器横向滚动；这是实现自审，不声称用户视觉批准。behavior-ui-rehearsal-fixed.log与result.json保留；runtime_switched/production_ready均false，自有PG/IdP/JVM/Vite已finally停止，子网91—92与数据保留，原8602未切换。首轮主动中止的审计计数修正证据保留。

完整425项420PASS/5既有skip、36工具/237入口/122能力/34角色、前端Prettier/build、node/py_compile及最终两仓hygiene/diff通过；Java formatter/静态分析限制保持。两仓behavior-ui-source-sha256.json记录并复核验证源文件摘要一致，验证后无产品代码修改。B2本地DONE，Git交付中；下一CE04-C周期与周期权益，技术盘点完成，其他会员和CE05—08仍未完成。
