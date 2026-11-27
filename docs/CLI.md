# ij-format CLI

[简体中文](zh-CN/CLI.md) · [Documentation](../README.md)

The CLI sends UTF-8 source, language, and selection to the running IDEA plugin over local Streamable HTTP. IDEA can stay on its Welcome screen while it formats the source using the active IDE-wide code style. Exact column ranges require plugin 0.3.0 or later. Line and whole-file formatting also work with plugin 0.2.0.

## Install the CLI

The released CLI runs as a standalone executable. [Install methods, paths, and upgrades](INSTALL.md#install-the-cli) · [Build from source](CONTRIBUTING.md#go-cli)

## First connection

Install the ij-format plugin in IntelliJ IDEA. [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/36481-ij-format) · [Manual installation](INSTALL.md#install-the-idea-plugin)

Start IDEA and check for **Running** in **Settings → Tools → ij-format**. Run `ij-format status` to verify the connection and see the negotiated MCP protocol and available ranges. It can run without a source file and does not print the token.

The CLI automatically uses the connection file when exactly one standard IDEA profile matches. For multiple profiles or a custom profile, click **Copy CLI Config Path** and pass the path to each CLI invocation with `--config`. The option also accepts a file saved from **Copy MCP JSON**. Both files contain a local Bearer token.

```sh
config="/path/to/ij-format-mcp.json"
ij-format status --config "$config"
printf 'class Demo {\n  val n=1\n}\n' | ij-format --config "$config" - --language kotlin --lines 2:2
```

- If connection discovery fails or the server is unreachable, check that the plugin is enabled under **Settings → Plugins → Installed** and that **Settings → Tools → ij-format** shows **Running**.
- If authentication fails, use the current config path or copy the current MCP JSON from the IDEA instance you want to use.

## Format source

For an existing file, use `--lines` or `--range` to format the edited section. Add `--write` to apply the changes or use `--diff` to preview them.

Use `--timeout 3s` to override the deadline for each input. The range is 1 ms to 2 minutes. The plugin defaults to 15 seconds, including waiting for an HTTP worker and IDEA formatting. A busy server or timeout returns exit code `2`. Change the default deadline and in-flight limit in **Settings → Tools → ij-format**.

```sh
ij-format Foo.java --lines 12:38
ij-format Foo.java --range 12:5-14:18
ij-format - --language kotlin --lines 12:38 < Bar.kt
ij-format Foo.java --lines 12:38 --diff
ij-format Foo.java --lines 12:38 --write
ij-format Foo.java Bar.kt --whole-file --check
```

Run `ij-format` or `ij-format --help` to see the full command help. Existing scripts can still use `ij-format format ...`. For a file literally named `format` or `status`, use `./format` or `./status` when passing it as the first argument.

- `--lines FIRST:LAST` selects an inclusive, 1-based range in one file or stdin. Multiple files require `--whole-file`. Whole-file formatting of empty input is a no-op.
- `--range LINE:COL-LINE:COL` selects an exact range in one file or stdin. Both coordinates are 1-based, the start is included, and the end is exclusive. Each Unicode code point counts as one column, including tabs. The column immediately after the last character is a valid endpoint. A final line break allows an endpoint at column 1 of the following empty line. IDEA rejects any result that would change text outside the selection.
- Paths ending in `.java` or `.kt` select the language automatically. Stdin (`-`) and single files with other names need `--language java|kotlin`. Stdin accepts up to 128 KiB, like files.
- A single input prints the complete formatted source by default. Choose at most one output mode. `--diff` prints a unified diff. `--check` prints inputs that would change, using `-` for stdin. `--write` replaces changed regular files and preserves Unix permission bits. With multiple files, choose an output mode and let the CLI infer each language from its extension.
- Exit status is `0` for success, `1` when `--check` finds changes, and `2` for usage, input, formatting, or connection failures. Errors go to stderr. A batch processes valid files in order and returns `2` if any file fails.

One batch uses one MCP handshake and processes files sequentially, reusing the HTTP connection while the server keeps it open. With older plugins the HTTP client limit is 25 seconds per request. With plugin 0.4.0, the client allows the server's maximum formatting deadline plus five seconds for transport. An explicit `--timeout` sets that transport budget to the requested duration plus five seconds.

## Docker development containers (experimental)

On the host, enable the container endpoint through IDEA's custom VM options and restart IDEA.

- For macOS Docker Desktop, start with `-Dijformat.mcp.container.bind=127.0.0.1`.
- For Linux Docker Engine, use the host's private Docker bridge IPv4 address. Add `--add-host=host.docker.internal:host-gateway` when you start the container and verify that it resolves to that bridge address.

Use **Copy MCP JSON** in IDEA. In a copy of the configuration, change the URL's host to `host.docker.internal`, keeping the port and token. Mount the copy read-only into the container and keep it out of source control and image builds. Use it directly in an MCP client or run `ij-format status --container --config /run/secrets/ij-format.json`. Add the same flags to formatting commands. The plugin retains the normal loopback endpoint and requires the token on both endpoints. Use this feature only for trusted local containers.

## Integration test

On macOS, [build the CLI](CONTRIBUTING.md#go-cli) and [plugin ZIP](CONTRIBUTING.md#idea-plugin), then run the test in a graphical session with Python 3.9 or later. Pass `--cli` to use a different binary.

```sh
python3 integration-tests/test-cli.py --idea-home '/Applications/IntelliJ IDEA.app/Contents'
```

The test launches isolated IDEA instances with a project open and at the Welcome screen. It forwards one 100-file batch to the real plugin and verifies the MCP handshake count and TCP connection reuse.

By default, the test uses the plugin ZIP built from the current checkout and expects exact ranges. To test an existing 0.2.0 ZIP, pass `--archive /path/to/ij-format-mcp-0.2.0.zip --no-exact-range`.
