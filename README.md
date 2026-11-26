# ij-format

Format Java and Kotlin ranges with the native formatter and active IDE-wide code style from a running IntelliJ IDEA instance. Use the CLI from a terminal or the local MCP tool from a coding agent. IDEA can stay on the Welcome screen.

[简体中文](docs/zh-CN/README.md)

## Why ij-format?

For codebases already formatted in IDEA, targeted formatting keeps small edits in the existing style and limits the diff.

- `ktlint --format` selects Kotlin files by path or glob and applies its own rules to the whole file. The output can differ from IDEA’s code style. [ktlint usage](https://github.com/ktlint/ktlint#usage)
- `google-java-format` supports Java line ranges and character offsets. It applies Google Java Style, which can change indentation and wrapping in code formatted with IDEA. [google-java-format usage](https://github.com/google/google-java-format#using-the-formatter)
- IDEA’s built-in `format` takes files or directories and requires other IDEA instances to be closed. It has no line or column selection. [IDEA command-line formatter](https://www.jetbrains.com/help/idea/command-line-formatter.html)
- IDEA’s MCP `reformat_file` needs a path in an open project. Slow or stalled calls also interrupted our agent workflow. [IDEA MCP formatting tools](https://www.jetbrains.com/help/idea/mcp-server.html#formatting-tools)

ij-format takes source text and a line range or exact line-and-column selection. The plugin uses the active IDE-wide scheme and rejects changes outside the selection. The source project can stay closed. The CLI reuses the running IDEA instance and times out each HTTP request after 25 seconds.

## Quick start

1. Download the plugin ZIP. In IDEA, select **Settings → Plugins → ⚙ → Install Plugin from Disk**, choose the ZIP, and restart if prompted. Verified on IDEA 2026.1.1 (build `IU-261.23567.138`). Enable Kotlin support to format Kotlin. [Download the plugin ZIP](https://github.com/Yunie9/ij-format/releases/download/v0.3.0/ij-format-mcp-0.3.0.zip) · [IDEA installation help](https://www.jetbrains.com/help/idea/managing-plugins.html#install_plugin_from_disk)
2. Check for **Running** in **Settings → Tools → ij-format**.
3. On macOS or Linux, install the CLI for your CPU into `~/.local/bin`. Add that directory to your `PATH` to run `ij-format` by name. On Windows, extract the matching ZIP and add the directory containing `ij-format.exe` to your `PATH`. [Release downloads](https://github.com/Yunie9/ij-format/releases/tag/v0.3.0)

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

4. For an MCP client, click **Copy MCP JSON** and use the copied `mcpServers.ij-format` entry. To connect manually over Streamable HTTP, use the entry's `url` and `headers.Authorization`.

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
- Supported MCP versions are `2025-11-25`, `2025-06-18`, and `2025-03-26`.

## Server settings

The default endpoint is `http://127.0.0.1:49273/mcp`. Set `-Dijformat.mcp.port=49274` in IDEA's VM options to use a different port. The server listens only on loopback and the copied JSON contains a local access token. [Privacy and local data](docs/PRIVACY.md)

The HTTP pool defaults to 4 minimum workers, 4 maximum workers, and 20 waiting slots. Configure 1–32 workers (minimum ≤ maximum) and 1–256 waiting slots. Extra workers start when the queue fills. **Apply** updates a running server while keeping accepted requests and connections. When shrinking, the queue accepts new work once it drops below the new limit.

## Documentation

[Contributing](docs/CONTRIBUTING.md) · [Changelog](docs/CHANGELOG.md) · [Privacy](docs/PRIVACY.md)

## License

[Apache License 2.0](LICENSE) · [NOTICE](NOTICE) · [Third-party notices](docs/THIRD_PARTY_NOTICES.md)
