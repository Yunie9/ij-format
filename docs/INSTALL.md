# Install and connect

[简体中文](zh-CN/INSTALL.md) · [Quick start](../README.md#quick-start)

Install the IDEA plugin and CLI for the recommended terminal and coding-agent workflow. The plugin also exposes MCP. The plugin was verified on IDEA 2026.1.1. Enable Kotlin support to format Kotlin.

## Install the IDEA plugin

- **Marketplace** — In IDEA, open **Settings → Plugins → Marketplace**, search for **ij-format**, and click **Install**. Restart if prompted. [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/36481-ij-format)
- **Plugin ZIP** — Download `ij-format-mcp-<version>.zip` and select **Settings → Plugins → ⚙ → Install Plugin from Disk**. The same ZIP works on all platforms. [GitHub Release downloads](https://github.com/Yunie9/ij-format/releases/latest)
- **Script** — Quit IDEA before running the `plugin` command below, then reopen it. If you use a custom IDEA profile, pass its absolute plugins directory as the third argument.

## Install the CLI

- **CLI ZIP** — Choose `darwin`, `linux`, or `windows`, then `amd64` for Intel/AMD or `arm64` for ARM. Extract the ZIP and add the executable's directory to your `PATH`. [GitHub Release downloads](https://github.com/Yunie9/ij-format/releases/latest)
- **Script** — Run the `cli` command below. The script selects the ZIP and checks its SHA-256 checksum. Add the install directory to your `PATH`.

### macOS and Linux

Download the installer, then run the command for the component you want. To upgrade, download and pass the newer version. [GitHub Releases](https://github.com/Yunie9/ij-format/releases)

```sh
curl -fsSLo install.sh https://github.com/Yunie9/ij-format/releases/download/v0.4.0/install.sh
sh install.sh plugin 0.4.0  # IDEA plugin
sh install.sh cli 0.4.0     # CLI
```

### Windows

If PowerShell 7 is missing, install it with WinGet and open a new PowerShell 7 window. [Microsoft's PowerShell installation guide](https://learn.microsoft.com/en-us/powershell/scripting/install/installing-powershell-on-windows)

```powershell
winget install --id Microsoft.PowerShell --exact --source winget
```

In PowerShell 7, download the installer and run the command for the component you want.

```powershell
Invoke-WebRequest https://github.com/Yunie9/ij-format/releases/download/v0.4.0/install.ps1 -OutFile install.ps1 -ErrorAction Stop
Unblock-File -LiteralPath .\install.ps1
.\install.ps1 plugin 0.4.0  # IDEA plugin
.\install.ps1 cli 0.4.0     # CLI
```

### Default install directories

| Platform | CLI | IDEA 2026.1 plugin directory |
| --- | --- | --- |
| macOS | `~/.local/bin` | `~/Library/Application Support/JetBrains/IntelliJIdea2026.1/plugins` |
| Linux | `~/.local/bin` | `${XDG_DATA_HOME:-~/.local/share}/JetBrains/IntelliJIdea2026.1` |
| Windows | `%LOCALAPPDATA%\ij-format\bin` | `%APPDATA%\JetBrains\IntelliJIdea2026.1\plugins` |

Pass an absolute directory as the script's third argument to change the destination. Quote paths with spaces. To uninstall the CLI, delete the installed executable.

## Verify the connection

Open IDEA and check that **Settings → Tools → ij-format** shows **Running**.

- **CLI** — Run `ij-format status`. For multiple IDEA profiles or a custom profile, use **Copy CLI Config Path** and pass that path with `--config`. [CLI guide](CLI.md#first-connection)
- **MCP** — Click **Copy MCP JSON** and add the `mcpServers.ij-format` entry to your agent's configuration. For manual Streamable HTTP configuration, use the copied `url` and `headers.Authorization`.
