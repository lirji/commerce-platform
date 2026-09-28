# 崩溃与重启

测试包装器与真实JVM两层证据：

- JourneyRecoveryTest：真实事务内已经写benefit_grant后，在stepFinish前Error。权益、stepStart、checkpoint全部回滚，version/attempts不增；新服务轮转器恢复一次。
- 提交后Error：权益与历史已提交，后继从notice开始，不重做grant。
- 真实JVM：25秒WAIT SIGKILL与两个新进程恢复，历史/固定定义/检查点完全相同，未来不执行。
- 真实业务窗口：仅owned commerce_phase8_recovery中的临时trigger在GRANT stepFinish更新前SLEEP；此时源码顺序及事务测试证明权益已经写入。观察该owned连接User sleep，SIGKILL调用JVM并KILL该owned服务器连接，确认权益/trace0，另一进程恢复权益1和GRANT台账1。

server连接额外终止用于确定性释放MySQL未收到TCP断开时仍执行的SLEEP，不宣称只杀JVM能即时释放数据库连接。无生产故障开关、trigger或故障载荷入包。脚本finally清理trigger和owned进程。

初次探针混用root CST/UTC，修为显式UTC；第二次MySQL预编译执行processlist INFO为DO SLEEP而不是UPDATE，修为owned schema/状态筛选；与scale共享schema发生测试互扰后把故障探针移到独立recovery库，最终结果两项PASS。未把先前失败当产品成功证据。

正式：process_recovery.py，.local/phase8-real-process-final.log；小结果在本文件所述断言中归档，原private日志不提交。
