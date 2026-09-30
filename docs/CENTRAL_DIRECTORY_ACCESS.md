# 商家/门店中央员工目录

CE03-D1本地DONE，依赖auth2557de1（执行范围协议）。四个GET/POST admin/merchants、admin/stores逐能力判定，DIRECTORY族与库存独立。列表在SQL LIMIT前过滤并复核范围；创建仅完整TENANT_ALL，真实父商家由MerchantApi核对，无隐含merchant.read。Commands先锁路由再查回执/写入，稳定身份纳入幂等，实际merchant/store审计同事务；领域不携带Token。

完整401项Java396PASS/5可选skip，含3项新真实MySQL/HTTP验证；.local/central-inventory/directory-verify.log。真实auth/IdP/商城83项PASS：auth/.local/governance/p6/rehearsal-16693ea0d846/result.json，库存与CATALOG回归包含其中。hygiene无阻断，保留未配置Java formatter/静态分析限制。无浏览器验收，目录页面为后续D2。

V51兼容扩展DIRECTORY族与资源类型审计，保留V49/V50及状态触发器；历史库存写入仍兼容。所有实例认识DIRECTORY路由后才能切换CENTRAL；旧CE03-U二进制不检查该族，不能用回滚旧版本恢复ADMIN。安全回退使用认识DIRECTORY的版本并置STOPPED。5秒准入不是全局即时撤权承诺，事务仍原10秒上限。

仅隔离库演练，原8602/commerce_local未切换；真实映射/Owner/生产目标待定。专用MySQL43308保留供后续切片，不清理私密证据。

首次远程CI36667458010失败：构建脚本固定旧SDK来源，缺少executionScope，未到测试阶段。本地已安装D0 SDK所以未暴露。已将scripts/auth-sdk-source.ref固定为经过CI的auth2557de1；复用原安装脚本验证SDK源码精确一致，后续远程结果另记。

## CE03-D2目录员工页

固定/operations/directory与两个create-access只读提示；四独立权限不串权，无read也可create。真实游标列表/页内表单使用中央独立上下文，输入已知父商家，结果未知保持输入与原幂等键；401撤内容、403清旧数据、503可重试；两个保留Form采用独立name，避免相同merchantId标签指向隐藏输入。该片HTTP共223条；后续会员片新增4入口后为227条，见CENTRAL_MEMBER_ACCESS.md。

402项Java397PASS/5skip；最终前端build/Prettier/package/hygiene通过，保留既有大chunk及无Java formatter限制。auth rehearsal-ae9ef79aba17真实99检查PASS，目录11条浏览器含密码PKCE、只读/创建/仅创建、丢响应原键重试、撤权、跨租户、401/503和390视口；商家/门店各一次审计。关键1440/390/未知/故障截图已查看。首次工具exclusive文件冲突、主动中止及重复Form ID失败均保留auth CE03_DIRECTORY.md。D1修SDK pin后远程36667650544 SUCCESS；本片D2 Git/CI尚待记录。
