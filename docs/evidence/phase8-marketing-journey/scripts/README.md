# 可复跑验收

在仓库根目录执行，先按现有README构建最终UI jar并配置ignored `.local/runtime.env`。使用既有dev_infra MySQL8.4；不创建新公共中间件，不操作生产数据。

```sh
./scripts/build.sh
/opt/homebrew/bin/python3.11 docs/evidence/phase8-marketing-journey/scripts/process_recovery.py
/opt/homebrew/bin/python3.11 docs/evidence/phase8-marketing-journey/scripts/backlog_scale.py
/opt/homebrew/bin/python3.11 docs/evidence/phase8-marketing-journey/scripts/warm_recheck.py
docs/evidence/phase8-marketing-journey/scripts/browser-affected.sh
```

Python路径可替换为安装所需依赖的Python3.11。probe_support只允许commerce_phase8_bench/commerce_phase8_recovery schema；真实kill用独立recovery库。脚本创建当前探针唯一tenant，不DROP/RESET已有数据，不replay成功实例。只停止自己Popen启动的JVM；kill窗口临时owned DB trigger在finally清理，显式释放同schema owned连接。SQL连接和应用均UTC。原大日志、token、私密env与jar位于ignored.local，不归档。

默认jar为commerce-app/target/commerce-app-0.1.0-SNAPSHOT.jar；COMMERCE_PHASE8_PROBE_JAR可指定冻结已审查jar。真实process/scale与mutation不能同时运行于同schema/产品源码，故障probe完结后再规模复测，避免DDL干扰测量。

rolling_compatibility.py还需要ignored `.local/phase8-old-main` 中从aa8bef1源码归档正常构建的旧jar，不能用新jar改标签冒充旧版本。旧版本按原依赖和UI打包流程构建；此脚本真实启动两个binary，结果明确旧trace覆盖不足。

mutation_checks.py只在无其他源码修改/构建并发时执行，三个指定变异每次恢复文件原字节hash，预期为具体行为测试失败而非编译失败。运行后必须重新执行scripts/build.sh生成干净最终jar。故障代码局限测试包装/临时owned数据库trigger，不进入生产配置/包。

容量脚本50k/100/1000/10000/热点SQL夹具用于查询与调度压力，真实入组、支付与WAIT另有独立证明，不能把夹具声称为50k次完整业务API入组。结果在.local/phase8-process自动生成，归档时只提取无敏感数据的指标与SQL计划。
