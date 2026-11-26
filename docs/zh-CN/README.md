# ij-format

[English](../../README.md)

使用运行中的 IntelliJ IDEA 的原生格式化引擎和全局代码风格，格式化 Java 或 Kotlin 的指定范围。终端用 CLI，Agent 可连接本地 MCP。IDEA 停在欢迎页也能使用。

## 为什么做 ij-format？

对已经按 IDEA 风格格式化的存量代码，局部格式化可以沿用原有风格，减少无关 diff。

- `ktlint --format` 按路径或 glob 选择 Kotlin 文件，整文件应用自身规则，结果可能与 IDEA 风格不同。 [ktlint 用法](https://github.com/ktlint/ktlint#usage)
- `google-java-format` 支持 Java 行范围和字符偏移，采用 Google Java Style，可能改变存量 IDEA 代码的缩进和换行。 [google-java-format 用法](https://github.com/google/google-java-format#using-the-formatter)
- IDEA 自带的 `format` 命令以文件或目录为单位，无法指定行列范围，并要求关闭正在运行的 IDEA 实例。 [IDEA 命令行格式化](https://www.jetbrains.com/help/idea/command-line-formatter.html)
- IDEA 自带 MCP 的 `reformat_file` 需要已打开项目中的文件路径。实际使用时，响应慢或卡住也会打断 Agent 的编辑流程。 [IDEA MCP 格式化工具](https://www.jetbrains.com/help/idea/mcp-server.html#formatting-tools)

ij-format 接收源码和选区，支持整行和精确行列范围。插件使用 IDEA 的全局风格，拒绝越出选区的改动。源码所属项目无需打开。CLI 复用运行中的 IDEA，并限制请求等待时间。 [超时与容器接入](CLI.md)

## 安装并连接

1. 下载插件 ZIP，在 IDEA 的 **Settings → Plugins → ⚙ → Install Plugin from Disk** 中选择它，按提示重启。已在 IDEA 2026.1.1（`IU-261.23567.138`）验证。格式化 Kotlin 需启用 Kotlin 支持。 [下载插件 ZIP](https://github.com/Yunie9/ij-format/releases/latest) · [IDEA 安装说明](https://www.jetbrains.com/help/idea/managing-plugins.html#install_plugin_from_disk)
2. 在 **Settings → Tools → ij-format** 确认服务状态为 **Running**。
3. 下载并解压对应操作系统和 CPU 架构的 CLI ZIP，将二进制所在目录加入 `PATH`。从 v0.4.0 起，macOS 和 Linux 用户也可从同一 Release 下载 `install.sh`，传入版本号。脚本校验发布包后将 CLI 安装到 `~/.local/bin`。 [下载 CLI](https://github.com/Yunie9/ij-format/releases/latest)

   ```sh
   sh install.sh cli 0.4.0
   ```

4. **MCP 客户端** — 点击 **Copy MCP JSON**，把 `mcpServers.ij-format` 合并到兼容客户端，或使用其中的 `url` 和 `headers.Authorization` 手动配置 Streamable HTTP。

v0.4.0 的安装脚本也支持在 macOS 和 Linux 上安装插件。下载 Release 附件中的 `install.sh`，退出 IDEA 后运行 `sh install.sh plugin 0.4.0`，完成后再启动 IDEA。脚本默认用 IDEA 2026.1 的插件目录，自定义配置可将插件目录的绝对路径作为第三个参数。

## CLI 用法

```sh
# 检查 IDEA 连接和支持的范围类型
ij-format status

# 格式化 Java 第 12 到 38 行并写回
ij-format Main.java --lines 12:38 --write

# 精确到列格式化 Kotlin 并写回
ij-format Example.kt --range 12:5-14:18 --write

# 按需预览格式化差异
ij-format Main.java --lines 12:38 --diff

# 从标准输入读取 Kotlin，输出格式化结果
ij-format - --language kotlin --lines 2:6 < Example.kt

# 检查多个文件是否需要格式化
ij-format Main.java Example.kt --whole-file --check
```

CLI 指南介绍了范围规则、退出码和如何用 `--config` 选择 IDEA 配置。 [完整 CLI 指南](CLI.md)

## 让 Coding Agent 使用 CLI

将以下内容加入项目的 `AGENTS.md`。

```md
修改 Java 或 Kotlin 代码后，用 `ij-format` 格式化本次改动及必要的上下文。

- 首次使用时，运行 `ij-format --help` 查看用法，再用 `ij-format status` 检查 IDEA 连接。
- 默认用 `--write` 写回。按整行选区用 `--lines`，需要精确到列时用 `--range`，仅在需要预览时用 `--diff`。示例：`ij-format Example.kt --range 12:5-14:18 --write`。
- 对已有文件，除非用户或项目明确要求，否则不得执行全文件格式化。
```

## 工具参数和行为

`ij_format_lines` 接受 `language`、`source` 和范围参数。按行时传 `startLine` 和 `endLine`，按列时传 `range`。 [调用示例](../../README.md#mcp-tool)

- 支持 `java` 和 `kotlin`，行范围从 1 开始并包含首尾。0.3.0 可以用 `range` 按行列定义精确范围，结束位置不包含在选区内。输入上限 128 KiB，保留一致的 LF 或 CRLF。 [CLI 坐标规则](CLI.md)
- 运行时使用 IDEA 的全局代码风格，即使打开项目也独立处理输入的源码。项目代码风格、EditorConfig、SDK 和 Classpath 不参与格式化。
- 成功时返回完整文本，调用方自行决定如何使用结果。如果 IDEA 要改动选区外文本，工具返回错误，可以扩大选区后重试。
- v0.4.0 可用 `timeoutMs` 覆盖默认请求时限（1–120000 毫秒），排队上限仍生效。错误码 `-32010` 表示繁忙，`-32011` 表示排队超时，`-32012` 表示执行超时。
- 支持 MCP 协议 `2025-11-25`、`2025-06-18` 和 `2025-03-26`。

## 服务设置

默认地址为 `http://127.0.0.1:49273/mcp`。多个 IDEA 实例可分别在 VM options 设置端口，例如 `-Dijformat.mcp.port=49274`。复制的 JSON 含访问令牌，开发容器接入需显式启用。 [隐私和本地数据](PRIVACY.md) · [容器接入](CLI.md#docker-开发容器实验性)

v0.4.0 默认最小 2、最大 8 个 HTTP worker，格式化队列有 20 个等待槽。请求积压时加开 worker，多余的空闲 worker 会回收。点击 **Apply** 热更新线程数、队列容量和时限，后续请求使用新时限。

## 其他文档

[CLI 指南](CLI.md) · [构建与贡献](CONTRIBUTING.md) · [变更日志](CHANGELOG.md) · [隐私说明](PRIVACY.md)

## 许可证

项目原创代码和文档采用 [Apache License 2.0](../../LICENSE)。另见[项目版权声明](../../NOTICE)和[第三方声明](THIRD_PARTY_NOTICES.md)。
