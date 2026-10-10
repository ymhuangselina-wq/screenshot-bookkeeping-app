# 阿里云函数计算网关

- 运行时：Custom Runtime（需提供 Python 3）
- 启动命令：`python3 server.py`
- 监听端口：`9000`（自动读取 `FC_SERVER_PORT`）
- 内存建议：512 MB
- 超时：60 秒；网关的 AI 上游请求最长等待 35 秒
- HTTP 触发器：无需认证（应用仍使用个人访问密钥鉴权）

环境变量：

- `DASHSCOPE_API_KEY`：百炼 API Key（必填）
- `DASHSCOPE_MODEL`：默认 `qwen3-vl-flash`
- `DASHSCOPE_BASE_URL`：默认北京兼容接口
- `CLOUDFLARE_UPSTREAM`：默认现有 Worker 地址

当前 `server.py` 直接处理账本、字段选项、AI 识别及保存；账本目录存储在 OSS，字段和记录调用飞书接口。

2026-10-05 更新需与新版 Android APP 一起发布：客户端请求通过 `X-Book-Id` 指定账本，避免多个函数实例使用不同的“当前账本”缓存。保存发现字段标识不匹配时会读取飞书字段重新校验，不会静默丢弃后再报空记录。

将更新包上传到现有函数的代码部署区域，保留原环境变量、函数角色及 HTTP 触发器设置，启动命令仍为 `python3 server.py`。部署后 `/health` 的 `build` 应为 `20261005.1-explicit-book-reliability`。仅安装 APP 不会让这些服务端修复生效。
