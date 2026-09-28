# P3-02 商城范围与检查点验收

Validation: PASS（当前切片真实SQL与HTTP）；全阶段回归及远程交付见auth P3-07/P3_DELIVERY_RESULT。

- 新增CentralScopeMySqlTest六项全部通过：同Grant AND与跨路径OR、行/count/search/详情、游标失效；50+5行分批、幂等、重建服务恢复；排队/执行/已完成阶段撤权；双线程同版本仅一个提交；注入行唯一冲突导致批次、命令和检查点一起回滚后可重试；配额及跨主体检查点隔离。
- 真实双auth JVM + admin + commerce + Casdoor授权码/PKCE + PostgreSQL/SpiceDB/MySQL，48项检查PASS，详细见evidence/http-result.json。第二auth在撤权提交后图仍旧时立即503；receipt完成后两个节点都拒绝，新商城请求403。旧游标/任务不能借新Grant恢复；商品独立Grant仍可用。
- commerce进程SIGKILL后新PID恢复已提交50行检查点，最终55行无重复、下载实时复核；中央服务停机不会回退旧ACL。
- 第一次全量回归发现新增测试的无Web环境配置错误，已改RANDOM_PORT；既有ItemRetryIsolationTest一次受机器负载超出时间窗口，8项定向复核通过。首轮HTTP工具重复创建0600配置文件失败，改为唯一文件名后48项全程通过。保留失败日志，不把部分成功算PASS。
- SQL迁移V47及实际表/列注释已验证。授权与Owner字段绑定经过源码审查；没有浏览器SQL/ScopePlan可信输入，没有用户Token持久化。

最终全仓mvn verify exit0：383项，378通过、5既有条件跳过，零失败/错误。固定SDK更新至auth637385b后，最终常量化改动重新编译并通过CentralScopeMySqlTest六项。Code Hygiene COMPLETE_WITH_LIMITATIONS（无既有formatter/静态分析器）；非阻断固定预算提示经人工复核。
