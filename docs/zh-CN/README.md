# ij-format

[English](../../README.md)

**ij-format 是一款为增量修改和 Coding Agent 设计的 Java / Kotlin 格式化工具。** 它使用 IntelliJ IDEA 的原生格式化引擎，按行或精确到列处理指定范围，沿用当前 IDEA 的全局代码风格，减少无关的 Git diff。推荐在终端或 Coding Agent 中使用 CLI，也支持通过 MCP 直接调用。IDEA 停在欢迎页也能使用。

## 为什么 ij-format？

对已经按 IDEA 风格格式化的存量代码，局部格式化可以沿用原有风格，减少无关 diff。我们调研过很多已有的 java/kotlin 代码格式化工具，都无法完美满足这个目标：

- `ktlint --format` 按路径或 glob 选择 Kotlin 文件，只支持全文件，且结果可能与 IDEA 风格不同。 [ktlint 用法](https://github.com/ktlint/ktlint#usage)
- `google-java-format` 支持 Java 行范围和字符偏移，采用 Google Java Style，但可能改变存量 IDEA 代码的缩进和换行。 [google-java-format 用法](https://github.com/google/google-java-format#using-the-formatter)
- IDEA 自带的 `format` 命令以文件或目录为单位，无法指定行列范围，并要求关闭正在运行的 IDEA 实例。 [IDEA 命令行格式化](https://www.jetbrains.com/help/idea/command-line-formatter.html)
- IDEA 自带 MCP 的 `reformat_file` 要求先打开文件所在项目。实际使用时，响应速度和稳定性问题较为突出，会频繁打断 Agent 的编辑流程。 [IDEA MCP 格式化工具](https://www.jetbrains.com/help/idea/mcp-server.html#formatting-tools)

ij-format 接收源码和选区，支持整行和精确行列范围。插件使用 IDEA 的全局风格，拒绝越出选区的改动。源码所属项目无需打开。CLI 复用运行中的 IDEA，并限制请求等待时间。 [超时与容器接入](CLI.md)

## 安装并连接

**推荐使用 CLI**，终端和 Coding Agent 都可以调用。IDEA 插件提供格式化服务，也支持 MCP 客户端直连。

退出 IDEA，复制对应平台的命令安装插件和 CLI。重新打开 IDEA 后，运行 `ij-format status` 检查连接。 [单独安装、Marketplace 和 MCP 连接](INSTALL.md)

**macOS 和 Linux**

```sh
curl -fsSL https://github.com/Yunie9/ij-format/releases/download/v0.4.0/install.sh |
  bash -c 's=$(cat); [[ $s ]] || exit 1; for c in plugin cli; do bash -s -- "$c" 0.4.0 <<< "$s" || exit; done'
```

**Windows（PowerShell 7）**

```powershell
$source = curl.exe -fsSL https://github.com/Yunie9/ij-format/releases/download/v0.4.0/install.ps1
if ($LASTEXITCODE -ne 0 -or !$source) { throw 'Installer download failed' }
$install = [scriptblock]::Create($source -join "`n")
& $install plugin 0.4.0; if ($?) { & $install cli 0.4.0 }
```

[用 WinGet 安装 PowerShell 7](INSTALL.md#windows) · [检查连接与安装目录](INSTALL.md#检查连接)

### 让 Coding Agent 安装

把下面的提示词复制给能在 IDEA 所在机器上执行命令的 Agent。

```text
帮我在这台机器上安装或升级 ij-format 的 IDEA 插件和 CLI。
按 https://github.com/Yunie9/ij-format/blob/main/docs/zh-CN/INSTALL.md 操作。
插件可从 https://plugins.jetbrains.com/plugin/36481-ij-format 安装，
也可使用 https://github.com/Yunie9/ij-format/releases/latest 的插件 ZIP。
从同一 GitHub Release 页面下载适合我操作系统和 CPU 的 CLI，并确认我使用的 IDEA 版本和配置目录。
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
