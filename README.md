# ij-format

Format Java and Kotlin with IntelliJ IDEA’s native formatter and your IDE-wide code style. The project provides a Go CLI and a local Streamable HTTP MCP server.

[English documentation](docs/README.md) · [简体中文文档](docs/zh-CN/README.md)

## Quick start

1. Download `ij-format-mcp-0.3.0.zip` from **Releases**. In IDEA, select **Settings → Plugins → ⚙ → Install Plugin from Disk** and choose the ZIP. Restart IDEA if prompted.
2. In **Settings → Tools → ij-format**, check that the server shows **Running**. The service works from the Welcome screen or an open project.
3. For terminal use, download the CLI archive for your OS and CPU from **Releases**, extract it, and put `ij-format` on your `PATH`. The Windows executable is `ij-format.exe`.
4. For an MCP client, click **Copy MCP JSON** and configure the client with the copied `mcpServers.ij-format` entry.

## CLI usage

```sh
ij-format status
ij-format Main.java --lines 12:38 --diff
ij-format Example.kt --range 12:5-14:18 --write
ij-format - --language kotlin --lines 2:6 < Example.kt
ij-format Main.java Example.kt --whole-file --check
```

IDEA 2026.1.1 is the verified host. The CLI guide explains range rules, exit codes, and selecting an IDEA profile with `--config`. [English CLI guide](docs/CLI.md) · [中文 CLI 指南](docs/zh-CN/CLI.md)

## Documentation

[Contributing](docs/CONTRIBUTING.md) · [Changelog](docs/CHANGELOG.md) · [Privacy](docs/PRIVACY.md)

## License

[Apache License 2.0](LICENSE) · [NOTICE](NOTICE) · [Third-party notices](docs/THIRD_PARTY_NOTICES.md)
