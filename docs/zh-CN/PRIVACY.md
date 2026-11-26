# 隐私与本机数据

[English](../PRIVACY.md) · [文档首页](README.md)

插件监听 `127.0.0.1`，要求客户端提供生成的 Bearer 令牌。源码和选区在 IDEA 内存中处理，结果返回给客户端。插件不会读取项目文件来补充上下文、保存提交的源码或调用远程格式化服务。插件无遥测。IDEA 和 MCP 客户端各有自己的隐私设置。

本地端点和令牌保存在当前 IDEA 配置目录的 `ij-format-mcp.json` 中。如果文件系统支持 POSIX 权限，插件会用 `0600` 权限写入。**Copy MCP JSON** 会把令牌复制到剪贴板。**Copy CLI Config Path** 只复制路径。请妥善保管令牌和保存的 MCP JSON。

服务开关、工作线程数和队列容量保存在 IDEA 应用设置中。IDE 日志记录服务启动、错误、本地端点和配置路径。要停止服务，在 **Settings → Tools → ij-format** 取消勾选 **Enable MCP server**，然后点击 **Apply**。

Go CLI 在本机读取指定文件或标准输入，并把源码发送到同一个回环端点。只有指定 `--write` 时，CLI 才会写回改动过的文件。
