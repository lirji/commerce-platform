# 平台分类字典

平台编码是开店和门店商品分类共用的权威引用。V84 扩展 V83 的字典，已有编码、启停及开店名称快照继续可读。平台树支持一级分组、二级分组或末级、三级末级。分组可以新增下级，使用场景选择末级。

## 后台维护

用平台账号进入「平台分类字典」。维护专岗可新增一级及下级，修改名称、排序和启用状态，每次填写原因。查找支持名称和编码。详情可查看最近 20 次变更的版本、状态、操作者、原因及时间。

同一层名称唯一，编码全局唯一。编码、类型和父级在创建后固定。停用分组会隐藏整个分支的可选项，子节点自身的启停状态仍可维护。归档后的分类、开店快照、商品分类引用继续可读。已停用的分类不能用于新开店和新建商品引用。所有平台写操作在数据库事务内再次核对专岗权限和版本，并追加历史。

现有平台运维可以读分类树。授权按凭据归属租户与操作者记录在 `platform_category_curator`。各租户管理员可以在自己的门店选择字典项，平台编辑路径需要单独的专岗。开发环境可用 `python3 scripts/provision-category-curator.py --schema commerce_local --output .local/platform-category-curator.json` 创建独立的本机凭据。凭据文件在本机保存，默认从签发时间起 30 天有效。

## 门店商品引用

「商品管理 → 类目与模板」的店内类目可选择平台末级。门店名称、店内父子关系和导航继续由门店配置，商品引用保持现有店内分类 ID。已有门店分类可逐步绑定，旧调用仅提交名称等字段时保留原关联。停用平台末级后，已使用它的商品可继续维护原有资料。

| 接口 | 使用场景 |
| --- | --- |
| `GET /v1/platform/commerce-categories` | 返回平台树所需节点和当前写权限 |
| `POST /v1/platform/commerce-categories` | 新增分组或末级，提交编码、父级、名称、类型、排序和原因 |
| `POST /v1/platform/commerce-categories/{code}` | 按 `expectedVersion` 更新名称、排序、启停并落历史 |
| `GET /v1/platform/commerce-categories/{code}/history` | 查询最近变更 |
| `GET /v1/operations/catalog-platform-categories?storeId=...` | 在店铺商品权限下获取可绑定末级 |

新增及修改接口使用现有 `Idempotency-Key`。旧的开店和商品分类 API 可继续读取已存资料。门店分类用 `platformCategoryCode` 绑定或改绑，用 `clearPlatformCategory` 清除。
