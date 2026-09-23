# LP09 商品经营资料与检索契约

目标：在已有SPU/SKU、版本调价和门店权限上补齐结构化类目、规格模板、商品详情/图片、条码和条件检索。复用MySQL/MyBatis和现有前端组件，不引入搜索或文件存储中间件。

## 类目与模板

类目按租户/门店隔离，最多三级。创建 `{categoryId,storeId,parentId?,name}`；父类目必须存在且启用。类目父级不可换绑，修改仅名称/ACTIVE或RETIRED及预期版本/原因。存在启用子类目或已绑定商品时不能停用。GET有界ID游标。数据库维护主键及父级外键，不物理删除。

模板按门店、templateId/version不可变发布，`{templateId,version,storeId,name,fields:[{name,values:[]}]}`，1–8个唯一属性，每属性1–50个唯一允许值。模板引用精确版本，不跟随最新；不生成任意脚本。

## 商品详情及条码

SPU经营详情独立版本：`{storeId,expectedVersion,categoryId?,templateId?,templateVersion?,description,images:[{url,alt}],reason}`。说明纯文本<=5000字，最多6图，只允许无凭据HTTPS地址或受控站内/media路径，不服务器抓图。旧商品无详情返回version0、空图，兼容原category文字。绑定类目需有效；模板第一次绑定只允许尚无SKU的SPU，之后引用不可换绑，已有自由规格商品保留原约定，避免把旧组合强行解释成新模板。

SKU创建在SPU锁内读取模板并严格验证属性全集及允许值，仍使用既有规范化组合唯一约束。绑定模板和创建SKU按同一商品锁串行化，防止同时操作绕过约束。

SKU条码独立资料版本，`{storeId,expectedVersion,barcode?,reason}`，空串规范为null；非空为1–64个ASCII字母数字及`._-`，按门店唯一、大小写规范化为大写，不声称通过GS1校验。重复写由数据库唯一约束裁决，不允许不同SKU抢占。修订保留命令审计，独立于金额版本，不改成交快照。

## 查询与权限

经营端新增 /v1/operations/catalog-categories（创建/列表/修订）、/specification-templates（创建/列表）、/products/{id}/merchandising（读/写）、/skus/{id}/barcode（读/写）、/catalog-search（检索）。所有操作复用StoreAccessApi.requireCatalog，撤权后旧链接也不能读写。

会员 /v1/catalog/search 与 /v1/catalog/items/{skuId}：只提供已上架SKU及商品详情，按Actor租户和有效门店过滤。条件含storeId、q（标题/条码文字<=64）、categoryId、minimumPrice/maximumPrice、after/limit<=100；经营端可额外按ACTIVE/FROZEN过滤。稳定SKU ID升序分页，不接受任意排序列或SQL。价格精确两位、小于等于最大值。图片/条码来自后端真实持久数据；详情不暴露操作者或历史。

旧 /skus、报价和交易权威价格契约不变；检索结果不替代下单时价格/库存校验。当前关系库筛选适合现有规模，不承诺百万目录模糊查询性能；没有容量证据前不新增ES。

## 验收

真实MySQL验证跨店授权、父级深度/停用、模板非法组合、模板绑定与SKU创建并发、条码唯一/更新冲突、检索上下架及租户隔离、旧接口兼容；浏览器从类目/模板到商品详情、条码和会员筛选完整闭环。

精确模板读取：GET /operations/specification-templates/{id}/{version}?storeId，提供已绑定旧版本的允许值。包内/media公共插图无需Bearer，不开放文件写入；业务数据接口仍须认证。模板绑定功能应在所有商品写入实例升级后启用，旧写入器不会校验新模板。
