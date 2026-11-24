# ij-format MCP

通过本机 IntelliJ IDEA 的原生格式化引擎，格式化 Java 或 Kotlin 的指定行。插件只暴露一个 MCP 工具 `ij_format_lines`。它读取 IDEA 当前的全局代码风格方案，接收独立源码和行范围，返回完整文本。

[English](../README.md) · [构建和贡献](../CONTRIBUTING.md) · [变更日志](../CHANGELOG.md)

## 安装并连接

1. [构建插件 ZIP](../CONTRIBUTING.md)，在 IDEA 的 **Settings → Plugins → ⚙ → Install Plugin from Disk** 中安装。已验证 IntelliJ IDEA 2026.1.1，构建号 `IU-261.23567.138`。使用 Kotlin 格式化需要启用 IDEA 的 Kotlin 支持。
2. 打开 **Settings → Tools → ij-format MCP**。服务默认开启，即使 IDEA 停在欢迎页也会启动。在此页修改开关后点 **Apply** 即时生效。
3. 点击 **Copy MCP JSON**，把 `mcpServers.ij-format` 合并到兼容客户端。客户端配置格式不同，可以取出 `url` 和 `headers.Authorization`，添加 **Streamable HTTP** MCP 连接。

默认地址为 `http://127.0.0.1:49273/mcp`。多个 IDEA 实例可分别在 VM options 设置端口，例如 `-Dijformat.mcp.port=49274`。复制的 JSON 含访问令牌，请妥善保管。[隐私和本地数据](PRIVACY.md)

## 工具参数和行为

`ij_format_lines` 接受 `language`、`source`、`startLine` 和 `endLine`。[调用示例](../README.md#mcp-tool)

- 支持 `java` 和 `kotlin`，行号从 1 开始并包含首尾。输入上限 128 KiB，保留一致的 LF 或 CRLF。
- 运行时使用 IDEA 的全局代码风格，即使打开项目也独立处理输入的源码。项目代码风格、EditorConfig、SDK 和 Classpath 不参与格式化。
- 成功时返回完整文本，调用方自行决定如何使用结果。如果 IDEA 要改动选区外文本，工具返回错误，可以扩大选区后重试。
- 支持 MCP 协议 `2025-11-25`、`2025-06-18` 和 `2025-03-26`。


## 许可证

项目原创代码和文档采用 [Apache License 2.0](../LICENSE)。另见[项目版权声明](../NOTICE)和[第三方声明](../THIRD_PARTY_NOTICES.md)。
