# Codex Progress

## 任务目标

用户授权完成IAM P3，正常合并推送main并验证CI后暂停，不进入P4，不生产部署。

## 已完成

- auth P3全部10节点实现与本地验收；商城P3-02门店/商品ScopePlan、Owner SQL、游标、详情、分批导出与下载复查。
- 真实MySQL六项新增测试；全仓383项零失败（5既有条件跳过）；最终范围定向复核通过。
- 双auth节点/Casdoor PKCE/图/MySQL真实HTTP48项通过；真实商城SIGKILL后50+5行恢复及撤权后下载拒绝。
- SDK源码固定auth637385b，安装、Boot4兼容通过。具体证据见docs/implementation/oa-auth/phase-3。

## 已修改文件

- runtime/api/scope、store/access、catalog/product的范围Owner接口及Mapper。
- commerce-app/iam、http/store、V47中央范围检查点迁移及真实MySQL测试。
- scripts/auth-sdk-source.ref、scripts/iam-scope-smoke.py、P3契约/运行/验收文档。

## 未完成

- 商城无剩余实施；55578e0/525bc7a正常合并推送main dceeb5a，CI36440692582成功。
- 本轮文档提交后检查其自动CI；整体P3最终收尾由auth权威进度记录，完成后暂停。

## 当前问题

- 无用户输入阻塞。生产容量/保留清理未承诺，共享Casdoor升级HOLD仍保留。
- OA已有用户改动未动；.local私密证据、自有隔离数据库和图保留，不自动清理。

## 下一步建议

1. 商城只剩最终文档提交的适用CI确认；auth正在修复首轮Linux测试时间精度差异，不能提前声称其CI通过。
2. 读取auth P3_DELIVERY_RESULT完成整体收尾后停止，不自动进入P4。

## 恢复 Prompt

读取本文件及auth docs/design/oa-auth-unification/PROGRESS_STATE.md，从auth整体P3 CI/交付未完成部分继续，不重做已通过的P3，不进入P4。
