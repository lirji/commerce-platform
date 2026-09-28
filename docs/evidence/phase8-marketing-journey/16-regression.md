# 回归与负向变异

最终clean命令`scripts/build.sh` PASS；应用297/0 failures/0 errors/5 configured benchmark skips，architecture3、marketing27、order45、kernel3零失败；前端tsc/Vite构建与UI+API入jar验证通过。原Phase2–7业务/规则/支付/退款/积分/券/权益/扫描/运行时恢复/留存/权限/观测回归均保留。

Phase8新增Graph4/Recovery7/Persisted相关7真实DB场景覆盖。当前事实撤销→NO_MATCH、不同传输eventId同orderId去重均通过。旧Phase7偶发Journey失败本轮未复现，仍不宣称已找到全部历史根因。

三项mutation：MATCH改走no、WAIT丢资格时间、失败去原版本守卫。每项都由指定行为断言准确失败（非编译失败），finally源字节hash完全还原；之后最终clean全套通过。负向脚本不进入app package，产品没有故障flag/trigger。

最终jar affected browser4/4（26.8s）：真会员下单/收款/履约/售后退款CREDIT冲正、规则/低代码预览审批窄屏、Journey可视编排入组站内触达、生日扫描发券效果。UI产品无需重设计。

Code hygiene：IMPLEMENTATION_COMPLETE_WITH_LIMITATIONS，BLOCKING=0；仓库没有Java/Python canonical formatter或静态分析配置，周边风格/diff检查通过。枚举/固定预算/只读事务advisory人工审查：独立配置ValidationCode不是DomainException第二套业务异常；StepState是执行证据而非payment生命周期；分页50/超时10沿原限制，无远程IO或中间件发布。

远程main aa8bef1历史CI：BUILD/测试成功，全量browser20/24，四个失败为旧按钮名称、统计文案与主题断言。对应独立fix/ci-browser-contracts提交bdc2af7已修正测试适配，保留图片加载/过滤/行为记录/券真实撤销等业务断言。最终新jar全量24/24通过（1.2min），日志.local/phase8-ci-browser/browser.log；未改前端产品。远程CI结果绑定实际Git ref后在交付结果归档，不把历史红CI写成绿。

日志均在ignored.local：phase8-clean-build.log、phase8-browser-affected.log、phase8-mutation-summary.log、phase8-hygiene-final.log。运行生成凭据与应用密码不归档。
