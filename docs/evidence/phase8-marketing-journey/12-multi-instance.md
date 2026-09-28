# 多实例

process_recovery.py实际启动两个独立Java进程/HTTP端口8628和8629，各有自己的Spring上下文、连接池、TenantRotation。WAIT到期两个同时pump，返回各1（一个执行GRANT，另一个可能在其提交后执行END），最终仅1权益，WAIT/GRANT/END共3step。没有把线程共享singleton测试当作独立实例证据。

rolling_compatibility.py另用旧main aa8bef1和新二进制两个JVM共库，旧读新、新读旧、入组重复与partial历史PASS。

真实MySQL故障测试还覆盖竞争失败回滚后被健康执行器抢先推进：旧失败不增attempt、不写后继失败。数据库row lock+SKIP LOCKED+version guard与领域唯一来源一起收敛；无需分布式锁或新租约基础设施。
