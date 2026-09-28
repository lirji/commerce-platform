# Codex Progress

## 任务目标

完成企业IAM P2后暂停。auth-platform/docs/design/oa-auth-unification是主计划；本仓承担P2-06商城只读接入。用户已授权正常提交、合并和推送main。

## 已完成

- auth P2全部9节点实现和本地验收完成，正在Git/CI交付；不进入P3。
- 商城中央门店读取、显式身份映射、V46迁移及真实SQL过滤完成；旧Actor/memberId/回执及其他路由保留。
- 真实跨进程13项PASS，非HTTP直调用例2项PASS，全量mvn verify退出0。
- 本任务分支feat/oa-auth-p2-commerce；原前端进度保存在.local/oa-auth-p2/PREVIOUS_CODEX_PROGRESS.md。

## 已修改文件

- commerce-app/iam、StoreAccessController、V46与Mapper、SDK依赖、固定源码安装、真实链路脚本、CI和P2文档；以任务commit为准。

## 未完成

- 正常合并推送及远程CI最终观察、auth阶段报告同步。

## 当前问题

- 无新阻塞。旧共享Casdoor升级HOLD保留，无生产部署或OA用户文件修改。
- 未引入细粒度范围、多实例投影或跨请求ALLOW缓存，这些属于P3。

## 下一步建议

1. 完成两仓Git/CI收口；失败只修复本任务问题。
2. 更新最终交付证据，然后暂停；不得自动启动P3。
3. 保留.local中的私密配置/隔离资源，不清库、不强制删除、不覆盖共享图。

## 恢复 Prompt

读取本文件及auth的CODEX_PROGRESS和P2_DELIVERY_RESULT，只完成尚未结束的P2交付观察；P2完成后暂停。只有用户新授权才能开始P3。
