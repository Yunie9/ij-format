# Privacy and local data

ij-format MCP listens on `127.0.0.1` and requires a generated Bearer token. It processes the source text and line range provided by the MCP client in the running IDE and returns the resulting text to that client. It does not read project source files to supply context, write the submitted source to disk, or send it to a remote formatter. The plugin has no telemetry. IntelliJ IDEA and the MCP client have their own settings and policies.

The plugin stores the local endpoint and token in `ij-format-mcp.json` in the active IDEA configuration directory. On POSIX systems the file is written with mode `0600`. **Copy MCP JSON** places the token in the system clipboard. Treat this token and the connection file as credentials.

The plugin stores the server's enabled state in IDEA's application settings. Its IDE log records startup and errors, including the loopback endpoint and connection file path. To stop the service, turn off **Enable MCP server** in **Settings → Tools → ij-format MCP** and apply the change.
