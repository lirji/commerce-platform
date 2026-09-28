# 安全与权限

HTTP原admin范围只允许ADMIN，精确会员新增history路径登记在MEMBER_PATHS，未开放恢复/preview/定义管理。service再次admin鉴权；认证Actor.tenant唯一来源，接口不接受tenant覆盖。

history固定instance tenant，会员经members.current只读本人；他人/他租户404，匿名401，非法limit400。JourneyRecoveryTest证明同租户其他member无history/无recovery；Persisted测试证明admin foreign404、member admin403，preview越tenant订单/会员过滤沿原API。

SQL只位于Mapper XML，值绑定、分页1..50稳定cursor；固定节点枚举拒绝script。通知文本由现有UIReact文本节点展示；不使用innerHTML。日志和failure trace仅分类/标识，未保留原异常文本、Bearer、手机号或地址。

保留历史不包含完整会员事实；既有审计及恢复审计默认不清理。无真实生产部署、全库清空、强推或生产测试数据。
