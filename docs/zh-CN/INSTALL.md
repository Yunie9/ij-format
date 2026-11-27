# 安装并连接

[English](../INSTALL.md) · [快速开始](README.md#安装并连接)

推荐安装 IDEA 插件和 CLI，终端和 Coding Agent 都可以调用。插件也提供 MCP 服务。插件已在 IDEA 2026.1.1 验证。格式化 Kotlin 需启用 Kotlin 支持。

单独运行安装脚本时，从要安装的版本下载 `install.sh` 或 `install.ps1`，并传入对应版本号。 [发布附件](https://github.com/Yunie9/ij-format/releases/latest)

## 安装 IDEA 插件

- **Marketplace** — 在 IDEA 的 **Settings → Plugins → Marketplace** 搜索 **IJ-format**，点击 **Install**，按提示重启。 [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/36481-ij-format)
- **插件 ZIP** — 下载 `ij-format-mcp-<版本>.zip`，在 IDEA 中选择 **Settings → Plugins → ⚙ → Install Plugin from Disk**。各平台使用同一个插件 ZIP。 [GitHub Release 下载](https://github.com/Yunie9/ij-format/releases/latest)
- **安装脚本** — 退出 IDEA 后运行 `sh install.sh plugin 0.4.0` 或 Windows 上的 `.\install.ps1 plugin 0.4.0`，再重新打开 IDEA。使用自定义 IDEA 配置时，将插件目录的绝对路径作为第三个参数传入。

## 安装 CLI

- **CLI ZIP** — 按系统选 `darwin`、`linux` 或 `windows`，再按 CPU 选 `amd64`（Intel/AMD）或 `arm64`。解压后将可执行文件所在目录加入 `PATH`。 [GitHub Release 下载](https://github.com/Yunie9/ij-format/releases/latest)
- **安装脚本** — 运行 `sh install.sh cli 0.4.0` 或 Windows 上的 `.\install.ps1 cli 0.4.0`。脚本会选择对应的 ZIP 并校验 SHA-256。安装后将所在目录加入 `PATH`。

## 从 GitHub 同时安装插件和 CLI

退出 IDEA，运行对应平台的命令，完成后重新打开 IDEA。

### macOS 和 Linux

```sh
curl -fsSL https://github.com/Yunie9/ij-format/releases/download/v0.4.0/install.sh |
  bash -c 's=$(cat); [[ $s ]] || exit 1; for c in plugin cli; do bash -s -- "$c" 0.4.0 <<< "$s" || exit; done'
```

### Windows

如果还没有 PowerShell 7，先用 WinGet 安装，再打开一个新的 PowerShell 7 窗口。 [微软 PowerShell 安装指南](https://learn.microsoft.com/en-us/powershell/scripting/install/installing-powershell-on-windows)

```powershell
winget install --id Microsoft.PowerShell --exact --source winget
```

在 PowerShell 7 中运行下列命令。

```powershell
$source = curl.exe -fsSL https://github.com/Yunie9/ij-format/releases/download/v0.4.0/install.ps1
if ($LASTEXITCODE -ne 0 -or !$source) { throw 'Installer download failed' }
$install = [scriptblock]::Create($source -join "`n")
& $install plugin 0.4.0; if ($?) { & $install cli 0.4.0 }
```

### 默认安装目录

| 平台 | CLI | IDEA 2026.1 插件目录 |
| --- | --- | --- |
| macOS | `~/.local/bin` | `~/Library/Application Support/JetBrains/IntelliJIdea2026.1/plugins` |
| Linux | `~/.local/bin` | `${XDG_DATA_HOME:-~/.local/share}/JetBrains/IntelliJIdea2026.1` |
| Windows | `%LOCALAPPDATA%\ij-format\bin` | `%APPDATA%\JetBrains\IntelliJIdea2026.1\plugins` |

脚本的第三个参数可指定安装目录的绝对路径。路径包含空格时加引号。卸载 CLI 时删除已安装的可执行文件。

## 检查连接

打开 IDEA，在 **Settings → Tools → IJ-format** 确认状态为 **Running**。

- **CLI** — 运行 `ij-format status`。有多个 IDEA 配置或使用自定义配置时，点击 **Copy CLI Config Path**，用 `--config` 传入该路径。 [CLI 指南](CLI.md#首次连接)
- **MCP** — 点击 **Copy MCP JSON**，将 `mcpServers.ij-format` 条目加入 Agent 客户端配置。手动配置 Streamable HTTP 时，使用其中的 `url` 和 `headers.Authorization`。
