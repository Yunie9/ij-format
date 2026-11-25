# Privacy and local data

[简体中文](zh-CN/PRIVACY.md) · [Documentation](../README.md)

The ij-format plugin listens on `127.0.0.1` and requires a generated Bearer token. It formats submitted source and the selected line or column range in memory, inside the running IDE, and returns the result to the calling client. It does not read project source files for context, write the submitted source to disk, or contact a remote formatter. The plugin has no telemetry. IntelliJ IDEA and the MCP client have their own settings and policies.

The plugin stores the local endpoint and token in `ij-format-mcp.json` in the active IDEA configuration directory. On POSIX systems the file is written with mode `0600`. **Copy MCP JSON** places the token in the system clipboard. **Copy CLI Config Path** copies the path to that file. Treat the token and any saved MCP JSON as credentials.

The plugin stores the server's enabled state in IDEA's application settings. Its IDE log records startup and errors, including the loopback endpoint and connection file path. To stop the service, turn off **Enable MCP server** in **Settings → Tools → ij-format** and apply the change.

The Go CLI reads the supplied paths or standard input locally and sends the source to the same loopback endpoint. It writes changed input files only when invoked with `--write`.
