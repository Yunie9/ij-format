# 变更日志

[English](../CHANGELOG.md) · [文档首页](README.md)

## 0.4.0

- HTTP 工作线程按负载弹性扩容，格式化使用独立的有界队列。
- 在 IDEA 配置请求和排队时限，也可通过 CLI 或 MCP 覆盖单次请求时限。
- 提供实验性的开发容器接入，可手动启用到宿主机 IDEA 的连接。
- macOS 和 Linux 用户可用安装脚本校验发布包并安装或升级 CLI 和插件。

## 0.3.0

- 新增 Go CLI，支持文件、标准输入、查看差异、检查和写回。
- 为 MCP 工具和 CLI 增加精确行列范围，继续兼容行范围请求。
- 多文件批次顺序处理，共用一次 MCP 握手和 HTTP 连接。
- 用 `ij-format status` 检查 IDEA 连接。IDEA 设置页可复制当前 CLI 配置路径，CLI 也接受从剪贴板保存的 MCP JSON。
- 插件和设置页显示名改为 **ij-format**。线程数和等待队列容量支持在线调整，保留当前连接和已接收请求。

## 0.2.0

- 通过本地 MCP 工具 `ij_format_lines` 格式化 Java 和 Kotlin 的指定行。
- 使用当前 IDE 全局代码风格处理独立源码，打开项目时也一样。
- 支持从欢迎页或打开项目时自动启动服务。
- 在 **Settings → Tools → ij-format MCP** 开关服务和复制连接 JSON。
- 通过 IntelliJ Platform Gradle Plugin 2.x 打包插件并验证兼容性。
