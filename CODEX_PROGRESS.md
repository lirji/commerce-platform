# Codex Progress

## 任务目标

执行已授权P6首批commerce-platform既有运营租户本地隔离迁移；用户选择2，完整CATALOG能力。详见../auth-platform/CODEX_PROGRESS.md。

## 已完成

- 完整中央经营桥、持久任务引用、服务端权威路由和数据库旧写冻结；原commerce_local及运行8602未切换。
- 5项新增真实MySQL测试通过：全经营、拒绝/到期/任务、回退、HTTP、并发切换等待旧事务。
- auth私有rehearsal-a5836d80f8c5真实跨进程31项通过；1真实源拒绝+1独立有限时正向夹具，不混淆生产身份。
- 最终完整reactor393项（388通过、5可选性能跳过），失败0；新5项包含并发切换，架构依赖测试通过。

## 已修改文件

- Actor、StoreAccessService、CatalogAuthority与API/Mapper/V48、CentralCatalogService/Configuration、任务重试、错误边界、5项MySQL测试、CI迁移专用连接。

## 未完成

- 无剩余产品代码；hygiene通过，局限为无统一formatter/静态分析未配置。
- 本文件为实施验收检查点；最终提交/main包含关系/远程CI以auth的phase-6/P6_DELIVERY_RESULT.md与CI_RESULT.md为准，恢复时先查这些文件和Git，不重新实施。
- SDK钉auth fc82340761b30936f3efde2e28df44f1cb6c3dde。

## 当前问题

- V48触发器需独立DDL Owner；测试失败迁移已用受控Flyway恢复，不删除数据、不改共享MySQL全局变量。
- 分支feat/iam-p6-migration，尚未提交；.local私有证据与运行配置保留，不入库。

## 下一步建议

1. 读取auth进度统一收尾；不要重启原商城。
2. 运行必要验证、记录测试/候选限制并完成Git交付。

## 恢复 Prompt

读取../auth-platform/CODEX_PROGRESS.md继续P6，用户已确认选项2完整CATALOG，无需再确认；保留原到期拒绝与OA用户改动。
