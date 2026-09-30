# 中央员工积分经营

CE04-PTS1以MEMBER_POINTS独立能力族接管原积分员工用例，五能力均为真实TENANT_ALL：政策read/publish、钱包与账本read、人工adjust、显式expire。旧ADMIN在CENTRAL/STOPPED不能绕过，V57仅扩族，不自动迁移租户或授予岗位。会员本人和内部积分结算保持原身份。

政策使用集合许可，发布审计实际points-policy-版本，历史不可变按version游标读取。钱包、账本、调整、到期先取实际Member Owner事实；读取结束复核scope指纹及会员版本。写入先在原Commands事务锁权威路由及会员、核验版本和准入截止，再读旧命令回执。稳定中央主体/成员代际进入摘要，旧模式原摘要保持，重试不会重复追加账本或审计。

人工调整保留ACTIVE会员、账户expectedVersion、非零±1e9整数和256字符原因；正数需当前有效政策，负数先扣有效批次、不足计待偿扣回。会员版本与账户版本职责不同。政策获取率仍为两位精确小数、0—1000，过期天数1—366、pointsPerYuan1—100000、抵扣比例0—10000bps。发布规则不赠送积分，也不改写历史订单奖励。

钱包按实际时间过滤已到期批次，查询不隐式写入。显式到期命令每次最多100批次，保留原冻结/已过期批次与退款语义；不要求积分读取权限，不宣称一次清理全部历史。账户、批次、账本、命令回执和员工身份审计同事务，审计故障整笔回滚。

客户本人钱包/账本、兑换和结算持有/退款沿现有流程，可信订单observe及系统到期任务不伪装员工身份，不因为员工撤权停止已承诺系统职责。人工调整沿现有审批流程，不新增OA逐笔审批。五秒只是本地准入截止，不承诺跨库瞬时撤权。

PTS1完整points-verify-fixed.log 438项433PASS/5既有skip，CentralPointsMySqlTest6方法PASS，包含三写审计故障回滚、无read独立命令、100/101到期边界、Owner/代际/撤权/STOPPED/503和系统事实/客户兑换去重。SDK/compile、36工具/243入口与hygiene通过，Java formatter/静态分析未配置限制保持。首轮测试编译参数次序错误已修并保留日志。真实中央联调验证中，PTS2页面尚未实现。

回退需使用认识MEMBER_POINTS接管族的兼容版本并置STOPPED；回退旧ADMIN版本不是安全回退。已发/扣/过期积分不会因代码回退撤销，纠正沿既有调整/退款流程，不能删账本或历史政策。本片不部署生产、不发布真实人员Grant、不清理数据。


### PTS1最终本地DONE

真实rehearsal-0c2048fafb9f最终329PASS，无浏览器，points_checked=true且runtime_switched/production_ready=false。独立publish无policy.read、adjust/expire无points.read、真实Owner缺失/跨租户、精度/版本冲突、原键重试、不可变未来策略与游标、实际到期钱包及ADJUST/EXPIRE账本、客户本人兼容、三写撤权后原回执拒绝、实际中央停机503通过。政策2+调整1+到期1恰4条真实目标身份审计；到期演示仅在自有库将本次批次时间前移，未宣称实际30天经过。全部既有域API与周期系统履约/进程恢复回归保持。

完整438项433PASS/5既有skip、36工具/243入口/122能力/34角色、SDK/compile及两仓hygiene通过；Java formatter/静态分析限制保持，两仓points-source-sha256摘要复核一致。V57已应用不可改，验证后无代码变化。自有PG/IdP/JVM已finally停止，子网97数据/私密证据保留，原8602未切换。PTS0 CI36681665256 SUCCESS（包含C2 auth基线）；C2 commerce CI36681436387原版重跑中，前次成长并发屏障超时保留。PTS1本地DONE，Git交付中；下一PTS2页面，随后CE04-O及CE05—08，生产输入HOLD不变。

C2 commerce CI36681436387第二次原版运行SUCCESS；未修改并发测试或预算，首次屏障超时证据保留。


## PTS2验证中

PTS1已普通合并推送auth67d4978/commercef1b99cb，两仓CI36682762638/36682764819 SUCCESS。PTS2在独立分支实现 /operations/member-points 固定SSO页：政策/钱包账本查询与三个独立命令、精确GET hints。业务DTO保留实际积分字段，政策spendEnabled显式布尔选择；调整保留原因/expectedVersion，原键未知重试；到期最多100批次且结果只显示真实钱包。无需附赠积分或会员读取权限。

points-ui-verify.log完整439项434PASS/5既有skip，CentralPointsMySqlTest7项PASS，前端build/Prettier通过；36工具与247入口（122能力、34角色）契约一致，两仓hygiene无阻断、Java formatter/静态分析限制保留。points-ui-source-sha256记录auth4/commerce11源码。真实rehearsal-b5f6eeb0aebf子网98运行中，完成并查看截图前不标DONE；未改V57或运行基础设施。


### PTS2最终本地DONE

真实rehearsal-b5f6eeb0aebf（子网98）397项PASS，包含积分11条浏览器检查和全部已交付员工页面回归。实际PKCE登录、独立无read的发布/调整/到期、显式false消费抵扣、输入精度与零值校验、真实409保留输入、三写服务端成功丢响应后原键/体重试、切Tab/取消退出保持未知意图、真实钱包/游标账本、撤写保留读、跨租户清除、401卸载及实际中央停机503均通过。SQL核对API4+UI3恰7条身份审计，UI实际policy-3、会员调整/到期各一次，UI账本恰ADJUST/EXPIRE两条、账户version2/available0，无重复效果。

完整439项434PASS/5既有skip、前端build/Prettier、36工具/247入口/122能力/34角色和两仓hygiene通过；Java formatter/静态分析限制保持。实际1440/390表单、列表/钱包、未知、冲突及停机截图已查看；两仓points-ui-source-sha256摘要复核一致。自有PG/IdP/JVM/Vite已finally停止、数据/截图保留；runtime_switched/production_ready=false，原8602未切换。无新迁移，V57不改。PTS2本地DONE待正常Git交付，下一CE04-O0积分商品协议，CE04-O1/O2和CE05—08未完成。
