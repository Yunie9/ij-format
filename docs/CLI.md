# ij-format CLI

[简体中文](zh-CN/CLI.md) · [Documentation](../README.md)

The CLI uses the running IDEA plugin's Streamable HTTP endpoint. It reads UTF-8 source locally and sends the source, language, and selection. Use plugin 0.3.0 for exact column ranges. Line and whole-file formatting also work with plugin 0.2.0.

## Install the CLI

Extract the archive for your OS and CPU from the GitHub Release and put `ij-format` (or `ij-format.exe` on Windows) on your `PATH`. The executable runs without Go, Python, or Gradle installed.

To build from source, run this from the repository root with Go 1.22 or later.

```sh
go -C cli build -trimpath -ldflags='-s -w' -o ../build/ij-format .
```

Run `./build/ij-format` from the checkout or put it on your `PATH`. On Windows, use `../build/ij-format.exe` as the output path and run `ij-format.exe`.

## First connection

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
- Single-file output defaults to the complete formatted source on stdout. `--check` lists paths that would change. `--diff` prints a unified diff. `--write` replaces changed files and preserves their permission bits. These modes are mutually exclusive. Multiple files require an explicit mode and infer the language from each file extension.
- Exit status is `0` for success, `1` when `--check` finds changes, and `2` for usage, input, formatting, or connection failures. Errors go to stderr. A batch processes valid files in order and returns `2` if any file fails.

One batch uses one MCP handshake and processes files sequentially, reusing the HTTP connection while the server keeps it open. An Agent's shell may restrict access to the local port. In that case, the CLI reports the denied connection and the Agent can use its configured MCP tool.

## Integration test

Build the CLI as shown above and [build the plugin ZIP](CONTRIBUTING.md#idea-plugin), then run the macOS integration test with Python 3.9 or later. Use `--cli` to test a binary at a different path.

```sh
python3 integration-tests/test-cli.py --idea-home '/Applications/IntelliJ IDEA.app/Contents'
```

The test launches isolated IDEA instances with a project open and at the Welcome screen. It forwards one 100-file batch to the real plugin and verifies the MCP handshake count and TCP connection reuse.

By default, the test uses the plugin ZIP built from the current checkout and expects exact ranges. To test an existing 0.2.0 ZIP, pass `--archive /path/to/ij-format-mcp-0.2.0.zip --no-exact-range`.
