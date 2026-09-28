# P8-S4 验证结果

状态：PASS。不是纯Mock证明，均使用真实MySQL。

- 真实支付事件→固定发布版本→WAIT→当前事实DECIDE→CREDIT受理→END→既有权益事件AVAILABLE/ledger1。不同eventId相同order业务来源仍只1实例。
- WAIT后实际撤销标签→NO_MATCH→无grant。preview不写Commands/权益，WAIT后明确FUTURE_DEPENDENT。
- 真实25秒WAIT提交后SIGKILL重启，checkpoint/wake不变，未到期0推进；到期两个新JVM竞争效果唯一。
- grant已写/stepFinish前test-only DB trigger窗口真实kill调用JVM，显式释放owned MySQL连接；业务/trace/checkpoint全部回滚，另一JVM恢复1grant/1ledger。所有trigger与owned进程清理。
- 测试事务包装覆盖commit后Error、瞬时依赖失败、永久失败五次隔离后原节点审计retry、失败回滚后另worker先推进的版本竞争。
- 实际OLD aa8bef1和NEW在V44/V45共存，互读、去重、恢复PASS；旧执行痕迹报告PARTIAL/LEGACY_PARTIAL，不伪造完整历史。
- v1在途/v2发布与pause、固定历史版本、另一会员与另一tenant、管理恢复权限均通过。

窄验证86项零失败；最终全构建包含以上所有测试。证据11-crash-restart.md、12-multi-instance.md、05-versioning-publication.md、14-security.md及相应脚本。private日志phase8-real-process-final.log、phase8-rolling.log。
