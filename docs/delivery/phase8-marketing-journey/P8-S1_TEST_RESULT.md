# P8-S1 Test Result

Gate PASS：同图结构校验、发布重检和纯预览实现后，真实 `scripts/verify.sh` 窄选择器包含所有必须有测试的模块；Money3、marketing27、order45、app9（5个MySQL/HTTP场景+4个图测试）、architecture3，87项零失败。日志 `.local/phase8-s1-verify-final.log`。

负例：missing entry、悬空边、不可达、重复ID、环、wait越界/零、缺权益、缺类型；HTTP未知enum400。预览有真实RuleDecision判定但grant/instance为0；WAIT只一节点并标未来依赖。非admin403、跨tenant404。发布在审批后引用权益失效409，原APPROVED保持。原WAIT/通知与创建图负例回归通过。

初次测试选择器没有architecture模块测试，failIfNoTests拒绝；补选ModuleBoundaryTest后通过，未改门禁。当前结果不认证后续trace/multi-instance/crash/performance。主Agent在实现后独立按验收读取断言与调用链，不声称外部独立审查。无新依赖/前端改动。最终源代码卫生与clean全套在P8-S5收口。
