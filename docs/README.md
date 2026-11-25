# ij-format

[简体中文](zh-CN/README.md)

Format Java or Kotlin with IntelliJ IDEA's native formatter and your IDE-wide code style. Use the Go CLI from a terminal or call the plugin's local MCP tool from an agent. Both work with standalone source text, including when IDEA is on the Welcome screen.

## Quick start

1. Download `ij-format-mcp-0.3.0.zip` from this repository’s **Releases** page. Select the ZIP in **Settings → Plugins → ⚙ → Install Plugin from Disk** and restart IDEA if prompted. The verified host is IntelliJ IDEA 2026.1.1, build `IU-261.23567.138`. Enable IDEA's Kotlin support for Kotlin formatting. [IDEA installation help](https://www.jetbrains.com/help/idea/managing-plugins.html#install_plugin_from_disk)
2. Open **Settings → Tools → ij-format** and check for **Running**. The service starts automatically when enabled, including at the Welcome screen.
3. **CLI** — Download and extract the CLI archive for your operating system and CPU from **Releases**. Put `ij-format` (`ij-format.exe` on Windows) on your `PATH`. [CLI examples](#cli-usage)
4. **MCP client** — Click **Copy MCP JSON**. Use the copied `mcpServers.ij-format` entry, or its `url` and `headers.Authorization` for a manual Streamable HTTP connection.

## CLI usage

```sh
# Check the IDEA connection and supported range types
ij-format status

# Preview changes to Java lines 12 through 38
ij-format Main.java --lines 12:38 --diff

# Format an exact Kotlin range and save the result
ij-format Example.kt --range 12:5-14:18 --write

# Read Kotlin from stdin and print the formatted source
ij-format - --language kotlin --lines 2:6 < Example.kt

# Check multiple files without modifying them
ij-format Main.java Example.kt --whole-file --check
```

Choose one of `--lines`, `--range`, or `--whole-file`. Lines and columns start at 1. `--lines` includes both endpoints, and `--range` excludes the end. For a single input, the default output is the complete formatted source on stdout. Exit codes are `0` for success, `1` when `--check` finds changes, and `2` for errors.

If multiple IDEA profiles are found, click **Copy CLI Config Path** in IDEA and pass the path with `--config`, for example `ij-format status --config "/path/to/ij-format-mcp.json"`. [Full CLI guide](CLI.md)

## MCP tool

```json
{
  "language": "java",
  "source": "class Demo {\n  void run(){int x=1;}\n}\n",
  "startLine": 2,
  "endLine": 2
}
```

- `language` is `java` or `kotlin`. Line numbers are 1-based and inclusive.
- Version 0.3.0 accepts an exact selection such as `"range": {"startLine": 12, "startColumn": 5, "endLine": 14, "endColumn": 18}` in place of the two top-level line fields. Columns are 1-based Unicode code points and the end is exclusive. [Coordinate rules](CLI.md)
- Requests accept up to 128 KiB of UTF-8 source. Uniform LF or CRLF line endings are preserved.
- The formatter uses the current IDE-wide scheme, even while a project is open. It works from the supplied source text without project-level code style, EditorConfig, SDK, or classpath.
- The result is the complete formatted source as MCP text content. If IDEA would change text outside the requested lines, the tool returns an error. Widen the range and retry when appropriate.
- Supported MCP protocol versions are `2025-11-25`, `2025-06-18`, and `2025-03-26`.

## Server settings

The default endpoint is `http://127.0.0.1:49273/mcp`. An IDEA instance can use a different port by setting `-Dijformat.mcp.port=49274` in its VM options. Only the loopback interface is used, and the JSON contains a local access token. [Privacy and local data](PRIVACY.md)

The HTTP pool defaults to 4 minimum workers, 4 maximum workers, and 20 waiting slots. Worker counts can be 1–32 with minimum ≤ maximum, and the waiting queue can hold 1–256 tasks. The pool starts extra workers when the waiting queue fills. **Apply** updates a running server without disconnecting clients. When shrinking, accepted requests finish and the queue admits new tasks once it drops below the new limit.

## Project documentation

[CLI guide](CLI.md) · [Contributing](CONTRIBUTING.md) · [Changelog](CHANGELOG.md) · [Privacy](PRIVACY.md)

## License

The original project code and documentation are licensed under the [Apache License, Version 2.0](../LICENSE). See the [project notice](../NOTICE) and [third-party notices](THIRD_PARTY_NOTICES.md).
