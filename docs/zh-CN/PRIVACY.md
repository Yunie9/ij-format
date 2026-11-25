# 隐私与本机数据

[English](../PRIVACY.md) · [文档首页](README.md)

ij-format 插件监听 `127.0.0.1`，并要求客户端提供生成的 Bearer 令牌。源码和选区在运行中的 IDEA 内存里处理，结果返回给调用方。插件不会为获取上下文而读取项目源码，不会把提交的源码写到磁盘，也不会联系远程格式化服务。插件无遥测。IDEA 和 MCP 客户端各自有自己的设置与政策。

插件把本地端点和令牌写入当前 IDEA 配置目录下的 `ij-format-mcp.json`。POSIX 系统上，文件的权限为 `0600`。**Copy MCP JSON** 会把令牌放入系统剪贴板。**Copy CLI Config Path** 只复制连接文件的路径。请妥善保管令牌和保存下来的 MCP JSON。

服务开关保存在 IDEA 应用级设置里。IDE 日志记录启动、错误、回环端点和配置文件路径。要停止服务，在 **Settings → Tools → ij-format** 中取消勾选 **Enable MCP server**，然后应用设置。

Go CLI 在本机读取指定文件或标准输入，并把源码发送到同一个回环端点。只有指定 `--write` 时，CLI 才会写回改动过的文件。
