# Privacy and local data

[简体中文](zh-CN/PRIVACY.md) · [Documentation](../README.md)

The plugin listens on `127.0.0.1` by default and requires a generated Bearer token. It formats the submitted source and selection in IDEA's memory and returns the result to the client. It does not read project files for context, save the submitted source, or contact a remote formatter. The plugin has no telemetry. IDEA and MCP clients have their own privacy settings.

The active IDEA configuration directory holds the endpoint and token in `ij-format-mcp.json`. On file systems with POSIX permissions, the plugin writes it with mode `0600`. **Copy MCP JSON** copies the token to the clipboard. **Copy CLI Config Path** copies only the file path. Keep the token and any saved MCP JSON private.

IDEA's application settings store the server toggle and worker and queue limits. The IDE log records startup and errors, including the local endpoint and config path. To stop the server, turn off **Enable MCP server** in **Settings → Tools → ij-format** and click **Apply**.

The Go CLI reads files or stdin and sends the source to the configured endpoint. It writes files only in `--write` mode. Opt-in container connections send source and token over HTTP on the development network. Pass only trusted local containers a read-only copy of the configuration.
