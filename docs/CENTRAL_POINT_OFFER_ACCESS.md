# 积分兑换商品员工权限

CE04-O1按已批准point_offer/TENANT_ALL接入read、define和status.update，独立POINT_OFFER接管族；V58仅扩展有限族，不切换任何原租户。SDK源码固定c0d0745。

员工精确HTTP为GET/POST /v1/admin/point-offers与POST /{id}/status。共享客户目录/v1/point-offers的非MEMBER调用也经过同一Service门禁，旧ADMIN不能绕过接管。门店只作真实业务归属和SQL过滤，不制造SPECIFIED_STORES授权；目录仍按tenant/store/offerId游标分页，返回前复核授权scope。

定义使用集合许可，事务route/期限guard先于旧回执；真实商家门店ACTIVE和券/权益版本资格保留，成功时审计实际offerId。停启先读实际商品Owner事实、判权，在原Commands事务锁route及实际商品版本、复核截止后读取旧回执；业务expectedVersion CAS保持。中央稳定身份进入幂等摘要，旧模式原摘要不变；两写的商品/状态/命令及身份审计同事务，失败一起回滚。

客户MEMBER自行兑换与本人回执沿原身份；会员锁先于商品锁，扣分、总额和个人额度、资产来源发放及回执维持原子性。员工撤权/STOPPED不等于取消客户自助；资产发放失败必须回滚扣分。停用商品只阻止新兑换，不撤销已经发出的券/权益。

本片不新增审批、基础设施或生产授权。回退需要认识POINT_OFFER族的兼容版本，可先STOPPED；已发生客户兑换不能靠代码回退撤销扣分或资产。生产人员和范围仍待Owner审核，五秒是本地准入期限。

O0已交付authc0d0745，252单元/真实PG c7f5dd5f5958+图10/Boot4/install/hygiene通过。O1 compile、36工具/247入口通过；CentralPointOfferMySqlTest6项与完整回归验证中，之后P6 --offers真实中央隔离联调。验证完成前不标DONE。O2员工页面及CE05—08未完成。

首次完整offers-verify.log发现ck_employee_resource未扩展导致6项新测试写审计失败，业务事务回滚。V58已应用不修改，追加V59允许point_offer实际目标且store_id必须为空，保留所有旧类型约束；修复后重新完整验证。

O1修复后完整offers-verify-fixed.log共445项440PASS/5既有skip，CentralPointOfferMySqlTest6项全部PASS；原积分兑换6项、成员成长并发/发券/公平性等回归保持。V58/V59已应用不可改，36工具/247入口、两仓hygiene与源码摘要auth1/commerce11一致。真实P6 --offers无浏览器子网99、session80627运行中；最终验收前不标DONE。O0 CI36684307399 SUCCESS，包含PTS2 auth基线。

O1首轮真实2f41e53f63a3/子网99在315PASS后因point_offer.define签发403停止。实际server运行JAR的嵌套governance仍是旧版，而当前governance模块Jar已有新能力：Maven增量repackage复用了旧归档。强制maven.jar.forceCreation=true package后，admin/server嵌套protocol/core/governance摘要与当前模块逐一一致，实际server class含新能力。P6现在先核验这些运行依赖再创建资源；新增离线回归复现旧嵌套依赖拒绝，37工具/247入口PASS。不改权限条件或等待预算。首轮证据及数据保留、自有进程finally停止；修复后子网100/session52939重新完整演练，日志offers-owner-rehearsal-fixed.log，最终前不标DONE。

第二轮真实79acb92b5686/子网100已通过337项（兑换商品定义/停启/读取和旧ADMIN旁路拒绝均通过），随后临时points.adjust夹具复用了早前积分模块的readiness证据文件名，私密文件O_EXCL拒绝覆盖并停止。修复只把offer_grant检查phase指定为offer-fixture，保留既有证据；37工具/语法通过，最终session73195/子网101重新完整验证，offers-owner-rehearsal-final.log。业务代码与445项回归版本保持不变。


### O1最终本地DONE

最终真实334c444f50a5/子网101共376项PASS，offers_checked=true，runtime_switched/production_ready=false。三权限真实独立、定义原键/实际商家门店与资产验证、停启原键及expectedVersion、实际Owner缺失/跨租户、管理目录稳定游标、旧ADMIN从管理与客户目录旁路均拒绝；两员工写撤权后旧回执403，read保留。单独临时points.adjust夹具授权已撤销，客户仍实际兑换：300积分扣100后200，原键仅1回执/1额度，第二次资产耗尽409后余额仍200。商品员工恰3条实际point_offer审计，客户兑换不伪装员工；中央停机时员工503，客户目录继续原本地身份。既有域API/系统履约/进程恢复全通过。

完整445项440PASS/5既有skip、37工具/247入口/122能力/34角色、compile、两仓hygiene和最终源码摘要auth2/commerce11一致。V58/V59已应用不可改。首次约束遗漏与两次演练制品/证据文件问题及修复均保留，未改权限或预算。当前运行Jar依赖已验证与模块一致；自有PG/IdP/JVM已finally停止，全部演练数据/私密证据保留，原8602未切换。O1本地DONE待正常Git交付；下一O2员工页面及两hint，其余CE05—08未完成。
