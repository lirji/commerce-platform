# P5商城运行

保留原配置。中央试点开启store-read.enabled和scope.enabled，私密store-read.configuration仍为0600。内部路由/协作路由/iam/callback仅静态壳，不授业务权限。前端构建VITE_IAM_ENABLED=true，VITE_IAM_AUTHORITY和VITE_IAM_CLIENT_ID固定服务配置；不填client_secret。回调精确登记<当前Origin>/iam/callback。开发COMMERCE_API_URL只决定Vite后端代理，默认8600，不来自浏览器输入。

P505命令：python3 scripts/iam-scope-smoke.py --p5-ui（真实完整回归）；--p5-only只缩短为内部主链。复用dev_infra MySQL，工具创建独立随机库/账号，不覆写旧库；每次新建P4 PG隔离库，端口18111/18112/18113/18603/18605仅回环进程，退出关闭。私密.local证据不提交。P507完整交互OIDC与静态打包验收待执行。
