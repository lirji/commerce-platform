# P8-S2 验证结果

状态：PASS。隔离MySQL执行V44成功，未修改历史迁移。

- `.local/phase8-s2-verify-final.log`：94项（kernel3 / marketing27 / order45 / app16 / architecture3），零失败。
- `.local/phase8-recovery-verify.log`：83项（含JourneyRecoveryTest5），零失败。
- 历史：WAIT资格时间、三值结果、动作真实关联、固定版本、稳定分页、tenant/本人权限、旧实例LEGACY_PARTIAL。
- 动作：权益受理REQUESTED与最终AVAILABLE区分；既有事件履约台账1行。券与通知沿用原API/独占Mapper，显式registry无fallback。
- 故障：效果已写但未完成时Error→业务/trace/检查点同回滚；提交后Error→重启不重放；瞬时故障不计毒次数；后续节点5次失败→原检查点隔离→审计重试，不重发早期权益。
- 并发失败窗口：失败事务回滚后另一执行器成功推进，旧失败不附到新节点也不增加次数。
- HTTP允许列表首次未登记会员history导致403，已补精确history路径，管理与恢复路径仍拒绝会员。初次测试规则误用协议字段已修正，最终均通过。

真实进程kill/WAIT重启、两独立进程、规模与滚动兼容仍由后续slice验证，不能用此结果代替。
