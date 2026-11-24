# ij-format MCP

Format selected lines of Java or Kotlin through IntelliJ IDEA's native formatter. The plugin exposes one local MCP tool, `ij_format_lines`. It accepts source text and a line range, applies the current IDE-wide code style, and returns the complete result.

[简体中文](docs/README.zh-CN.md) · [Contributing](CONTRIBUTING.md) · [Changelog](CHANGELOG.md)

## Install and connect

1. [Build the plugin ZIP](CONTRIBUTING.md) and install it from **Settings → Plugins → ⚙ → Install Plugin from Disk**. The verified host is IntelliJ IDEA 2026.1.1, build `IU-261.23567.138`. Kotlin formatting requires IDEA's Kotlin support to be enabled.
2. Open **Settings → Tools → ij-format MCP**. The service starts automatically when enabled, including at the Welcome screen. The setting applies immediately after you click **Apply**.
3. Click **Copy MCP JSON** and merge the `mcpServers.ij-format` entry into a compatible client. For a client with a different configuration format, use the copied `url` and `headers.Authorization` with **Streamable HTTP**.

The default endpoint is `http://127.0.0.1:49273/mcp`. An IDEA instance can use a different port by setting `-Dijformat.mcp.port=49274` in its VM options. Only the loopback interface is used, and the JSON contains a local access token. [Privacy and local data](docs/PRIVACY.md)

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
- Requests accept up to 128 KiB of UTF-8 source. Uniform LF or CRLF line endings are preserved.
- The formatter uses the current IDE-wide scheme, even while a project is open. It works from the supplied source text without project-level code style, EditorConfig, SDK, or classpath.
- The result is the complete formatted source as MCP text content. If IDEA would change text outside the requested lines, the tool returns an error. Widen the range and retry when appropriate.
- Supported MCP protocol versions are `2025-11-25`, `2025-06-18`, and `2025-03-26`.


## License

The original project code and documentation are licensed under the [Apache License, Version 2.0](LICENSE). See the [project notice](NOTICE) and [third-party notices](THIRD_PARTY_NOTICES.md).
