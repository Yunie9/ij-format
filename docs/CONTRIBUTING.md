# Contributing

[简体中文](zh-CN/CONTRIBUTING.md) · [Documentation](../README.md)

## Repository layout

- `idea-plugin/` is the Gradle project for the Kotlin IDEA plugin. It contains its own Wrapper, build configuration, and Java integration test starter.
- `cli/` is the Go module, with its own `go.mod`, command, and unit tests.
- `integration-tests/` contains the Python runners that exercise the packaged plugin and CLI in IDEA.
- `benchmarks/` contains the performance runners for current and historical plugin builds.
- `docs/` contains the CLI guide, contribution and release instructions, and other project documentation.

## IDEA plugin

Use JDK 21 and the Wrapper in `idea-plugin/`. Run these commands from the repository root. For Gradle import in IDEA, open `idea-plugin/` as the Gradle project.

```sh
./idea-plugin/gradlew -p idea-plugin buildPlugin compileTestJava verifyPlugin
```

Gradle resolves IntelliJ IDEA 2026.1.1 by default. To use a local installation, pass `-PideaHome`. The reports are under `idea-plugin/build/reports/pluginVerifier/`.
Install the resulting `ij-format-mcp-<version>.zip` from `idea-plugin/build/distributions/`.

```sh
./idea-plugin/gradlew -p idea-plugin buildPlugin verifyPlugin \
  '-PideaHome=/Applications/IntelliJ IDEA.app'
python3 integration-tests/test-idea-plugin.py --welcome \
  --idea-home '/Applications/IntelliJ IDEA.app/Contents'
```

The macOS test needs Python 3.9 or later. It runs the plugin ZIP in isolated IDEA processes using temporary settings and a local port. Run `--welcome` from a graphical login session to test the Welcome screen. Logs default to `idea-plugin/build/integration/`. The script builds and loads its own test starter.

## Go CLI

Use Go 1.22 or later. Run these checks from the repository root.

```sh
go -C cli vet ./...
go -C cli test ./...
go -C cli build -trimpath -ldflags='-s -w' -o ../build/ij-format .
```

On Windows, set the output path to `../build/ij-format.exe`.

Run the [CLI integration test](CLI.md#integration-test) against the packaged plugin on macOS. To verify that both Copy buttons produce working CLI configurations, add `--cli build/ij-format` to the plugin integration command above.

GitHub Actions runs Go checks on Ubuntu, macOS, and Windows, plus a Go 1.22 compatibility job on Ubuntu. The Ubuntu plugin job builds the ZIP, runs Plugin Verifier, and uploads the verified ZIP and any generated verifier reports as workflow artifacts.

## Releasing

Set `pluginVersion` in `idea-plugin/gradle.properties` and update both changelogs. Once CI passes, push an annotated `vMAJOR.MINOR.PATCH` tag for that commit. [English changelog](CHANGELOG.md) · [Chinese changelog](zh-CN/CHANGELOG.md)

Actions creates a Release draft from the English changelog and attaches the verified plugin ZIP, six CLI ZIPs (macOS, Linux, and Windows on `amd64` and `arm64`), and `SHA256SUMS`. Test the downloaded plugin and CLI together before publishing the draft.

If the Release already exists, the tag workflow stops at upload. Use the `idea-plugin` and `release-cli` artifacts from that run, generate `SHA256SUMS` for the seven ZIPs, and attach the files to the existing Release.

## Reporting issues

Include reproduction steps and a self-contained source sample. Remove credentials and private code from examples and logs.
