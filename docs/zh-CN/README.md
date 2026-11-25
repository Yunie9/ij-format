# ij-format

[English](../../README.md)

复用本机 IntelliJ IDEA 的原生格式化引擎和全局代码风格，格式化 Java 或 Kotlin。终端可以使用 Go CLI，Agent 可以直接调用插件的本地 MCP 工具。两种方式都只需要源代码，IDEA 停在欢迎页也可以使用。

## 安装并连接

1. 下载插件 ZIP。在 IDEA 的 **Settings → Plugins → ⚙ → Install Plugin from Disk** 中选择该 ZIP，按提示重启。已验证 IntelliJ IDEA 2026.1.1，构建号 `IU-261.23567.138`。格式化 Kotlin 需要启用 IDEA 的 Kotlin 支持。 [下载插件 ZIP](https://github.com/Yunie9/ij-format/releases/download/v0.3.0/ij-format-mcp-0.3.0.zip) · [IDEA 安装说明](https://www.jetbrains.com/help/idea/managing-plugins.html#install_plugin_from_disk)
2. 打开 **Settings → Tools → ij-format**，确认状态显示 **Running**。服务启动后，IDEA 停在欢迎页也能使用。
3. 在 macOS 或 Linux 上，以下命令会按 CPU 架构下载安装 CLI 到 `~/.local/bin`。将此目录加入 `PATH` 后即可直接运行 `ij-format`。Windows 用户请下载并解压对应架构的 ZIP，再把 `ij-format.exe` 所在目录加入 `PATH`。 [发布附件](https://github.com/Yunie9/ij-format/releases/tag/v0.3.0)

   ```sh
   (
     set -eu
     case "$(uname -s)" in
       Darwin) os=darwin ;;
       Linux) os=linux ;;
       *) printf '%s\n' 'Use the Windows ZIP from GitHub Releases.' >&2; exit 1 ;;
     esac
     case "$(uname -m)" in
       arm64|aarch64) arch=arm64 ;;
       x86_64|amd64) arch=amd64 ;;
       *) printf '%s\n' 'Unsupported CPU architecture.' >&2; exit 1 ;;
     esac

     tmp=$(mktemp -d)
     trap 'rm -rf "$tmp"' EXIT
     curl -fL "https://github.com/Yunie9/ij-format/releases/download/v0.3.0/ij-format-0.3.0-${os}-${arch}.zip" -o "$tmp/cli.zip"
     unzip -p "$tmp/cli.zip" ij-format > "$tmp/ij-format"
     mkdir -p "$HOME/.local/bin"
     install -m 755 "$tmp/ij-format" "$HOME/.local/bin/ij-format"
   )
   ```

4. **MCP 客户端** — 点击 **Copy MCP JSON**，把 `mcpServers.ij-format` 合并到兼容客户端，或使用其中的 `url` 和 `headers.Authorization` 手动配置 Streamable HTTP。

## CLI 用法

```sh
# 检查 IDEA 连接和支持的范围类型
ij-format status

# 预览 Java 第 12 到 38 行的改动
ij-format Main.java --lines 12:38 --diff

# 精确到列格式化 Kotlin 并写回
ij-format Example.kt --range 12:5-14:18 --write

# 从标准输入读取 Kotlin，输出格式化结果
ij-format - --language kotlin --lines 2:6 < Example.kt

# 检查多个文件是否需要格式化
ij-format Main.java Example.kt --whole-file --check
```

每次选择 `--lines`、`--range` 或 `--whole-file` 之一。行列号从 1 开始，`--lines` 包含首尾，`--range` 排除终点。单个输入默认将完整格式化结果输出到 stdout。退出码 `0` 表示成功，`1` 表示 `--check` 发现需要改动的文件，`2` 表示执行出错。

检测到多个 IDEA 配置时，在设置页点击 **Copy CLI Config Path**，通过 `--config` 指定路径，例如 `ij-format status --config "/path/to/ij-format-mcp.json"`。 [完整 CLI 指南](CLI.md)

## 工具参数和行为

`ij_format_lines` 接受 `language`、`source` 和范围参数。按行时传 `startLine` 和 `endLine`，按列时传 `range`。 [调用示例](../../README.md#mcp-tool)

- 支持 `java` 和 `kotlin`，行范围从 1 开始并包含首尾。0.3.0 可以用 `range` 按行列定义精确范围，结束位置不包含在选区内。输入上限 128 KiB，保留一致的 LF 或 CRLF。 [CLI 坐标规则](CLI.md)
- 运行时使用 IDEA 的全局代码风格，即使打开项目也独立处理输入的源码。项目代码风格、EditorConfig、SDK 和 Classpath 不参与格式化。
- 成功时返回完整文本，调用方自行决定如何使用结果。如果 IDEA 要改动选区外文本，工具返回错误，可以扩大选区后重试。
- 支持 MCP 协议 `2025-11-25`、`2025-06-18` 和 `2025-03-26`。

## 服务设置

默认地址为 `http://127.0.0.1:49273/mcp`。多个 IDEA 实例可分别在 VM options 设置端口，例如 `-Dijformat.mcp.port=49274`。复制的 JSON 含访问令牌，请妥善保管。 [隐私和本地数据](PRIVACY.md)

**HTTP workers** 默认最小 4、最大 4、等待队列 20。工作线程数可设为 1–32，最小值不得大于最大值，队列可设为 1–256。队列满后，线程池会按需扩到配置的最大值。点 **Apply** 立即更新运行中的服务，保留现有连接和已接收请求。缩容时，队列降到新上限以下后恢复接纳新任务。

## 其他文档

[CLI 指南](CLI.md) · [构建与贡献](CONTRIBUTING.md) · [变更日志](CHANGELOG.md) · [隐私说明](PRIVACY.md)

## 许可证

项目原创代码和文档采用 [Apache License 2.0](../../LICENSE)。另见[项目版权声明](../../NOTICE)和[第三方声明](THIRD_PARTY_NOTICES.md)。
