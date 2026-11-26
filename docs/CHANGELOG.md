# Changelog

[简体中文](zh-CN/CHANGELOG.md) · [Documentation](../README.md)

## 0.4.0

- Grow the HTTP worker pool under load and keep formatting in a separate bounded queue.
- Set request and queue timeouts in IDEA. Override the request timeout from CLI or MCP.
- Try the experimental opt-in connection from a Docker development container to the host's IDEA.
- Install or upgrade the CLI and plugin on macOS or Linux with a release installer that verifies archive checksums.

## 0.3.0

- Add a Go CLI for files and standard input, with unified diff, check, and write modes.
- Add exact line-and-column selections to the MCP tool and CLI. The original line-range request remains supported.
- Reuse one MCP handshake and HTTP connection for sequential multi-file CLI batches.
- Verify the IDEA connection with `ij-format status`, copy the active CLI config path from IDEA, and accept saved MCP clipboard JSON directly.
- Rename the plugin and its settings page to **ij-format** and apply worker and waiting queue limits live while preserving existing connections and accepted requests.

## 0.2.0

- Format a selected line range in Java or Kotlin through the local `ij_format_lines` MCP tool.
- Use the active IDE-wide code style for standalone source snippets, including while a project is open.
- Start automatically at the Welcome screen or when a project opens.
- Control the server and copy its connection JSON from **Settings → Tools → ij-format MCP**.
- Package and verify the plugin with IntelliJ Platform Gradle Plugin 2.x.
