# ij-format

**ij-format is a Java and Kotlin formatter designed for incremental edits and coding agents.** It uses IntelliJ IDEA’s native formatter and active IDE-wide code style to format line or line-and-column ranges and keep Git diffs focused. Use the CLI from a terminal or a coding agent. Agents can also connect through MCP. IDEA can stay on the Welcome screen.

[简体中文](docs/zh-CN/README.md)

## Why ij-format?

For codebases already formatted in IDEA, range formatting preserves the existing style and keeps diffs focused. The tools we evaluated each had trade-offs for this workflow.

- `ktlint --format` selects Kotlin files by path or glob and applies its own rules to the whole file. The output can differ from IDEA’s code style. [ktlint usage](https://github.com/ktlint/ktlint#usage)
- `google-java-format` supports Java line ranges and character offsets. It applies Google Java Style, which can change indentation and wrapping in code formatted with IDEA. [google-java-format usage](https://github.com/google/google-java-format#using-the-formatter)
- IDEA’s built-in `format` takes files or directories and requires other IDEA instances to be closed. It has no line or column selection. [IDEA command-line formatter](https://www.jetbrains.com/help/idea/command-line-formatter.html)
- IDEA’s MCP `reformat_file` needs a path in an open project. In our experience, slow or stalled calls frequently interrupt the agent’s workflow. [IDEA MCP formatting tools](https://www.jetbrains.com/help/idea/mcp-server.html#formatting-tools)

ij-format takes source text and a line range or exact line-and-column selection. The plugin uses the active IDE-wide scheme and rejects changes outside the selection. The source project can stay closed. The CLI reuses the running IDEA instance and applies request deadlines. [Timeouts and container setup](docs/CLI.md)

## Quick start

**Use the CLI** for terminal and coding-agent workflows. The IDEA plugin provides the formatter and also supports a direct MCP connection.

Quit IDEA and run the command for your platform to install the plugin and CLI. Reopen IDEA and run `ij-format status`. [Component installation, Marketplace, and MCP setup](docs/INSTALL.md)

**macOS and Linux**

```sh
curl -fsSL https://github.com/Yunie9/ij-format/releases/download/v0.4.0/install.sh |
  bash -c 's=$(cat); [[ $s ]] || exit 1; for c in plugin cli; do bash -s -- "$c" 0.4.0 <<< "$s" || exit; done'
```

**Windows (PowerShell 7)**

```powershell
$source = curl.exe -fsSL https://github.com/Yunie9/ij-format/releases/download/v0.4.0/install.ps1
if ($LASTEXITCODE -ne 0 -or !$source) { throw 'Installer download failed' }
$install = [scriptblock]::Create($source -join "`n")
& $install plugin 0.4.0; if ($?) { & $install cli 0.4.0 }
```

[Install PowerShell 7 with WinGet](docs/INSTALL.md#windows) · [Verify the installation and find the install directories](docs/INSTALL.md#verify-the-connection)

### Install with a coding agent

Copy this prompt into an agent that can run commands on the machine where IDEA is installed.

```text
Install or upgrade the ij-format plugin and CLI on this machine.
Follow https://github.com/Yunie9/ij-format/blob/main/docs/INSTALL.md.
Install the plugin from https://plugins.jetbrains.com/plugin/36481-ij-format
or use the plugin ZIP from https://github.com/Yunie9/ij-format/releases/latest.
Get the CLI for my OS and CPU from that GitHub release. Check which IntelliJ IDEA
installation and profile I use. If IDEA needs to close, let me save my work first.
After installation, run ij-format status and test line-range formatting on
temporary Java and Kotlin samples. Report the version, installation paths, and
test results. Keep the MCP access key private.
```

## CLI usage

```sh
ij-format status
ij-format Main.java --lines 12:38 --write
ij-format Example.kt --range 12:5-14:18 --write
ij-format Main.java --lines 12:38 --diff
ij-format - --language kotlin --lines 2:6 < Example.kt
ij-format Main.java Example.kt --whole-file --check
```

The CLI guide explains range rules, exit codes, and selecting an IDEA profile with `--config`. [English CLI guide](docs/CLI.md) · [中文 CLI 指南](docs/zh-CN/CLI.md)

## Using the CLI with coding agents

Add this snippet to your project's `AGENTS.md`.

```md
After editing Java or Kotlin code, use `ij-format` to format the changes and any necessary surrounding code.

- On first use, run `ij-format --help` for usage and `ij-format status` to check the IDEA connection.
- Write changes with `--write` by default. Use `--lines` for whole-line selections and `--range` for exact column boundaries. Use `--diff` when a preview is needed. Example: `ij-format Example.kt --range 12:5-14:18 --write`.
- Format an entire existing file only when the user or project explicitly requires it.
```

## MCP tool

Call `ij_format_lines` with the full source, language, and selection.

```json
{
  "language": "java",
  "source": "class Demo {\n  void run(){int x=1;}\n}\n",
  "startLine": 2,
  "endLine": 2
}
```

- Use `java` or `kotlin`. Line ranges are 1-based and inclusive. For an exact selection, replace the two line fields with `"range": {"startLine": 12, "startColumn": 5, "endLine": 14, "endColumn": 18}`. Columns count Unicode code points from 1 and the end is exclusive. [Coordinate rules](docs/CLI.md)
- The tool accepts up to 128 KiB of UTF-8 source and preserves uniform LF or CRLF line endings. It uses the active IDE-wide scheme and works from the supplied source text without project-level style, EditorConfig, SDK, or classpath.
- The result is the complete formatted text. If IDEA would change text outside the selection, the tool returns an error. Widen the range and retry if needed.
- Use `timeoutMs` to override the default formatting deadline (1–120,000 ms). At capacity the server returns HTTP 503. MCP errors `-32010`, `-32011`, and `-32012` indicate the queued source byte limit, timeout before formatting, and timeout after formatting starts.
- Supported MCP versions are `2025-11-25`, `2025-06-18`, and `2025-03-26`.

## Server settings

The default endpoint is `http://127.0.0.1:49273/mcp`. Set `-Dijformat.mcp.port=49274` in IDEA's VM options to use a different port. The copied JSON contains an access token. Developer container access is opt-in. [Privacy and local data](docs/PRIVACY.md) · [Container setup](docs/CLI.md#docker-development-containers-experimental)

Open **Settings → Tools → ij-format** and click **Apply** to update the server. Accepted requests keep their deadlines.

| Setting | Default and behavior |
| --- | --- |
| Minimum / Maximum workers | 2 / 8, each from 1 to 32. Bounds the HTTP worker pool |
| Scale-up threshold | 5, from 0 to 256. Allows the HTTP executor to add workers at this backlog |
| Maximum in-flight requests | 20, from 1 to 256. An admitted request holds a slot from reading its body through replying |
| Default request timeout | 15,000 ms, from 1 to 120,000. Includes HTTP executor wait, request processing, and waiting for IDEA to format |

Native formatting runs serially on IDEA's event dispatch thread. A 2 MiB internal limit protects queued source. Body reads use the default deadline. After parsing `timeoutMs`, the override is measured from the original dispatch time. Connection acceptance and blocked socket writes are managed by HTTP transport, with a separate client-side transport timeout. Upgrading uses the former formatting queue capacity as the initial in-flight limit and preserves the default request timeout.

The **MCP access key** is generated by default. Enter 16–256 ASCII letters, digits, underscores, or hyphens to set your own key. Omit the `Bearer` prefix. Clear the field and click **Apply** to generate a new key. New requests using the old key receive HTTP 401. The CLI reads the updated file on its next invocation. Update saved MCP JSON and container copies after rotating the key. [Local storage details](docs/PRIVACY.md)

## Documentation

[Contributing](docs/CONTRIBUTING.md) · [Changelog](docs/CHANGELOG.md) · [Privacy](docs/PRIVACY.md)

## License

[Apache License 2.0](LICENSE) · [NOTICE](NOTICE) · [Third-party notices](docs/THIRD_PARTY_NOTICES.md)
