# 图校验与预览

唯一校验入口JourneyGraph，创建、validate、publish、preview复用。图≤32节点，标识唯一、起点存在、边引用存在、全可达、无环；非终止路径最终到END。WAIT为1..604800秒且字段与kind互斥；总deadline为1..2592000秒。复杂规则必须是既有可信RuleNode字段，未知脚本和条件拒绝。

稳定ValidationCode供配置定位；当前返回首个有界issue，nodeId在全图错误可为空。HTTP未知kind会由严格JSON反序列化拒绝400，validate typed null kind为UNKNOWN_NODE_TYPE。

JourneyGraphTest4和Persisted相关HTTP测试证明缺入口、悬空边、重复、孤儿、环、越界WAIT、缺权益、未知类型、非可信字段不能发布。preview查询当前真实事实，WAIT返回FUTURE_DEPENDENT，action仅ACTION_NOT_EXECUTED；不入组、不占quota、不建command或通知。运行和预览共用三值branch。

证据：P8-S1_TEST_RESULT.md；负向变异结果在16-regression.md收口。
