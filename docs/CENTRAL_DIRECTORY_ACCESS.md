# 商家/门店中央员工目录

CE03-D1本地DONE，依赖auth2557de1（执行范围协议）。四个GET/POST admin/merchants、admin/stores逐能力判定，DIRECTORY族与库存独立。列表在SQL LIMIT前过滤并复核范围；创建仅完整TENANT_ALL，真实父商家由MerchantApi核对，无隐含merchant.read。Commands先锁路由再查回执/写入，稳定身份纳入幂等，实际merchant/store审计同事务；领域不携带Token。

完整401项Java396PASS/5可选skip，含3项新真实MySQL/HTTP验证；.local/central-inventory/directory-verify.log。真实auth/IdP/商城83项PASS：auth/.local/governance/p6/rehearsal-16693ea0d846/result.json，库存与CATALOG回归包含其中。hygiene无阻断，保留未配置Java formatter/静态分析限制。无浏览器验收，目录页面为后续D2。

V51兼容扩展DIRECTORY族与资源类型审计，保留V49/V50及状态触发器；历史库存写入仍兼容。所有实例认识DIRECTORY路由后才能切换CENTRAL；旧CE03-U二进制不检查该族，不能用回滚旧版本恢复ADMIN。安全回退使用认识DIRECTORY的版本并置STOPPED。5秒准入不是全局即时撤权承诺，事务仍原10秒上限。

仅隔离库演练，原8602/commerce_local未切换；真实映射/Owner/生产目标待定。专用MySQL43308保留供后续切片，不清理私密证据。
