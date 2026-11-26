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
3. 按下面的命令安装 CLI，或解压与系统和 CPU 匹配的 CLI ZIP，将二进制所在目录加入 `PATH`。

4. **MCP 客户端** — 点击 **Copy MCP JSON**，把 `mcpServers.ij-format` 合并到兼容客户端，或使用其中的 `url` 和 `headers.Authorization` 手动配置 Streamable HTTP。

### 各平台安装命令

从同一 v0.4.0 或更新的 Release 下载对应脚本。运行插件安装命令前先退出 IDEA，安装后重启。也可在 IDEA 中手动安装通用的 `ij-format-mcp-<版本>.zip`。 [发布附件](https://github.com/Yunie9/ij-format/releases/latest)

| 平台 | 安装 CLI | 安装 IDEA 插件 |
| --- | --- | --- |
| macOS | `sh install.sh cli 0.4.0` | `sh install.sh plugin 0.4.0` |
| Linux | `sh install.sh cli 0.4.0` | `sh install.sh plugin 0.4.0` |
| Windows（PowerShell 7） | `pwsh -NoProfile -File .\install.ps1 cli 0.4.0` | `pwsh -NoProfile -File .\install.ps1 plugin 0.4.0` |

脚本会按系统和架构选择 CLI ZIP 并校验 SHA-256。手动下载时选 `darwin`、`linux` 或 `windows`，再按 CPU 选 `amd64`（Intel/AMD）或 `arm64`。第三个参数可指定安装目录的绝对路径。 [安装目录和升级说明](CLI.md#安装-cli)

### 让 Coding Agent 安装

把下面的提示词复制给能在 IDEA 所在机器上执行命令的 Agent。

```text
帮我在这台机器上安装或升级 ij-format 的 IDEA 插件和 CLI。
使用 https://github.com/Yunie9/ij-format/releases/latest 的最新稳定版，
按 https://github.com/Yunie9/ij-format/blob/main/docs/zh-CN/README.md#安装并连接 操作。
根据操作系统和 CPU 选择同一版本的发布附件，并确认我使用的 IDEA 版本和配置目录。
需要退出 IDEA 时，请先让我保存工作。
安装后运行 ij-format status，再用临时 Java 和 Kotlin 样例各测一次行范围格式化。
告诉我安装版本、安装位置和验收结果，并为 MCP 鉴权密钥保密。
```

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
- 可用 `timeoutMs` 覆盖格式化请求的默认时限（1–120000 毫秒）。服务容量已满时返回 HTTP 503。MCP 错误码 `-32010` 表示达到排队源码上限，`-32011` 表示格式化前超时，`-32012` 表示格式化开始后超时。
- 支持 MCP 协议 `2025-11-25`、`2025-06-18` 和 `2025-03-26`。

## 服务设置

默认地址为 `http://127.0.0.1:49273/mcp`。多个 IDEA 实例可分别在 VM options 设置端口，例如 `-Dijformat.mcp.port=49274`。复制的 JSON 含访问令牌，开发容器接入需显式启用。 [隐私和本地数据](PRIVACY.md) · [容器接入](CLI.md#docker-开发容器实验性)

在 **Settings → Tools → ij-format** 调整以下参数。点击 **Apply** 生效，已接收的任务保留各自的时限。

| 设置 | 默认值及含义 |
| --- | --- |
| Minimum / Maximum workers | 2 / 8，范围 1–32。HTTP 工作线程上下限 |
| Scale-up threshold | 5，范围 0–256。HTTP 执行器积压达到此值时可扩容 |
| Maximum in-flight requests | 20，范围 1–256。接纳后从读取请求体到回包，全程占用一个名额 |
| Default request timeout | 15,000 毫秒，范围 1–120,000。计时包含 HTTP 执行器等待、请求处理及等待 IDEA 格式化 |

原生格式化在 IDEA 事件线程上串行执行，排队源码另有 2 MiB 内部保护。请求体读取使用默认时限，解析出 `timeoutMs` 后仍从原始接入时刻计算截止时间。连接接收和阻塞的 socket 写入由 HTTP 传输层管理，客户端另有传输超时。升级时沿用旧格式化队列容量作为初始总容量，并保留原请求时限。

**MCP access key** 默认自动生成。可填入 16–256 个英文字母、数字、下划线或连字符，无需填写 `Bearer` 前缀。清空并点 **Apply** 会生成新密钥。修改立即生效，旧密钥之后的请求会收到 HTTP 401。使用配置路径的 CLI 在下次启动时读取新密钥，保存过 JSON 的 MCP 客户端和容器需更新副本。 [本地存储说明](PRIVACY.md)

## 其他文档

[CLI 指南](CLI.md) · [构建与贡献](CONTRIBUTING.md) · [变更日志](CHANGELOG.md) · [隐私说明](PRIVACY.md)

## 许可证

项目原创代码和文档采用 [Apache License 2.0](../../LICENSE)。另见[项目版权声明](../../NOTICE)和[第三方声明](THIRD_PARTY_NOTICES.md)。
