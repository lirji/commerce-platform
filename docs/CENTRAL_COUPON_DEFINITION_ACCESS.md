# 券定义中央员工权限

沿auth CONTRACTS_COMMERCE_COUPON_DEFINITIONS和已批准扩展边界。SDK固定471cdb8853f3bbe388121428dfdfb805a6ef2510；COUPON_DEFINITION独立持久路由，read/create两能力、coupon_definition/TENANT_ALL，store仅实际业务归属和目录过滤。V60同时扩族与实际审计类型，无租户自动切换。

GET/POST /v1/admin/coupon-definitions精确接入中央员工身份。共享GET /v1/coupon-definitions的非MEMBER也走同一Service门禁，旧ADMIN不能旁路；客户仍只读PUBLIC最新版本。Mapper按tenant+definitionId最大version和门店/发行方式过滤、after稳定分页，不能回退暴露旧PUBLIC版本；读后复核集合身份/范围。目录不授予创建，创建不授予读取。

create在原Commands事务中先锁权威路由/核对期限再读旧回执；中央稳定主体代际进入原始定义输入摘要，旧模式摘要不变。实际门店及商家ACTIVE、金额精度/窗口/配额/发行方式/相对有效期校验沿原业务。定义与版本唯一、命令回执和employee_command_identity同事务；审计目标实际definitionId，版本保存在相同actor/operation/key命令响应，保持实际业务标识，不发明复合资源ID。审计失败整笔回滚，撤权后旧回执拒绝。

客户领取、钱包和可信积分/活动/定向/旅程来源发券保留原身份及原事务，中央员工STOPPED或撤权不能替换客户资格。已发券不因管理权限回退撤销。回退须使用识别COUPON_DEFINITION族的兼容版本；生产人员/映射仍由Owner审核，本片没有生产部署或OA逐笔审批。

CD0 auth已验证252单元/真实PG与图11方法/SDK Boot4和hygiene。CD1 compile通过，新增CentralCouponDefinitionMySqlTest6方法与完整回归验证中；auth37工具/250入口通过，真实跨进程尚未运行。V60一旦在本地应用不修改，CD2页面另片实施。Java formatter/静态分析未配置与五秒本地准入限制保持。


### CD1最终本地DONE

最终真实aa92413e7500/子网105共415PASS，无浏览器；coupon_definitions_checked=true，runtime_switched/production_ready=false。两能力独立、创建无read、原键/不可变版本冲突、真实商家门店Owner、精确金额、最新版本稳定分页及客户不泄露旧PUBLIC版本、跨租户与旧ADMIN两目录旁路拒绝、撤权后旧成功回执403、实际中央停机员工503/客户目录200全部通过。三次券定义恰3条实际definitionId身份审计；撤销创建和单独临时商品定义权限后，客户实际领取公开券并通过原积分兑换获得受控券，原键重试未重复发放，发行总数2、积分余额100。既有模块与后台执行引用/进程恢复检查保持。

完整452项447PASS/5既有skip、新6项全部PASS，37工具/250入口/122能力/34角色、compile/最终package、两仓hygiene及auth1/commerce8源码摘要一致。Java formatter/静态分析限制保留，V60已应用不可改。首次2项测试夹具错误及真实演练错误撤权路径均保留，修复不改业务边界或预算；自有进程finally停止，子网104/105私密证据/数据保留，原8602未切换。后端Git交付中，下一CD2页面及hint，其他CE05—08/生产2HOLD仍未完成。O2长度修复已有独立build/格式/hygiene证据，后续CD2真实浏览器回归。


## CD2员工页面

固定`/operations/coupon-definitions?tenant_id=<UUID>`，目录和创建两个Tab；独立`GET /v1/operations/coupon-definitions/create-access`不附赠目录权限。查询仅用已知门店和稳定游标，创建沿原API、精确金额字符串、显式布尔/发行方式与nullable字段。结果未知保留原键/体/路径；409可纠正、401卸载、403独立拒绝、503关闭表单。

CE05-CD2本地DONE：固定SSO券定义目录/创建两Tab、独立创建提示；完整453项448PASS/5既有skip，券定义7项全PASS；真实隔离83d4ad742f53（10.254.106.0/24）493PASS，其中券定义10条浏览器行为，含全部既有员工页回归及O2标识64字校准。37工具/252入口/122能力/34角色、build/Prettier/两仓hygiene与auth4/commerce11源码摘要一致。当前1440/390目录/表单、409、未知结果、退出确认、成功/503截图已查看。5条实际定义身份审计、UI两定义各1条，实际公开领取/受控兑换共2次且余额100。Java formatter/静态分析限制保留，无新迁移；V49—V60不可改。Git交付中，下一CE05-E权益定义/实例技术细化；其他CE05—08与生产2HOLD未完成。

证据在auth仓私密`.local/governance/commerce-contracts/coupon-definition-ui-*`及`.local/governance/p6/rehearsal-83d4ad742f53/`，正式验收见auth `docs/implementation/oa-auth/commerce-readiness/CE05_MARKETING.md`。自有进程已停止，原8602及本地权威库未切换。
