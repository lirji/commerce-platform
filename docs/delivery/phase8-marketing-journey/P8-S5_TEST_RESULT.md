# P8-S5 验证结果

状态：PASS_WITH_LIMITATIONS。验证后未变更Java/SQL产品源码；后续只补证据和独立四项浏览器旧断言修复。

- 最终`scripts/build.sh` clean：app297（0 failures/0 errors/5 configured benchmark skips）、architecture3、marketing27、order45、kernel3，全部成功；tsc/Vite及UI/API打包成功。
- 最终jar SHA256：74a16e41e86ff98928fc00ef09444dca097cb845bc735a535c4e2113b6c9baa9。
- affected browser4/4（26.8s）；独立fix/ci-browser-contracts bdc2af7四项旧UI断言修复与最终jar组合全24/24（1.2min）。没有修改产品UI。
- 三项定向mutation均准确被业务断言捕获：反转MATCH、取消WAIT资格、删除失败原版本守卫。源字节hash恢复后最终clean全套PASS。
- npm audit high门禁零漏洞；git diff --check PASS；hygiene BLOCKING=0。未配置Java/Python canonical formatter/static analysis，记录人工审查与限制，不虚称运行不存在的工具。
- J1–18均PASS；JRN1–20均证明适用不变量，JRN17注明路径/引用可解释，不保存完整历史Facts。

远程CI按不可变提交另由CI_RESULT/DELIVERY_RESULT绑定；此处不代替远程结果。证据16-regression.md、MATRICES.md、PHASE8_REPORT.md；private日志phase8-clean-build.log、phase8-ci-browser/browser.log、phase8-mutation-summary.log、phase8-hygiene-final.log、phase8-npm-audit.log。
