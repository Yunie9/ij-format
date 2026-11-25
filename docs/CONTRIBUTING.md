# Contributing

[简体中文](zh-CN/CONTRIBUTING.md) · [Documentation](README.md)

## Repository layout

- `idea-plugin/` is the Gradle project for the Kotlin IDEA plugin. It contains its own Wrapper, build configuration, and Java integration test starter.
- `cli/` is the Go module, with its own `go.mod`, command, and unit tests.
- `integration-tests/` contains the Python runners that exercise the packaged plugin and CLI in IDEA.
- `benchmarks/` contains the performance runners for current and historical plugin builds.
- `docs/` contains usage, verification, and release documentation.

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

The macOS integration script requires Python 3.9 or later. It runs the packaged plugin in isolated IDEA processes with temporary configuration and an ephemeral loopback port. Pass `--welcome` from a graphical login session to include the graphical Welcome screen. Reports and test logs remain under `idea-plugin/build/` by default. The separately compiled test starter is loaded only by the integration script.

## Go CLI

Use Go 1.22 or later. Run these checks from the repository root.

```sh
go -C cli vet ./...
go -C cli test ./...
go -C cli build -trimpath -ldflags='-s -w' -o ../build/ij-format .
```

Run the [CLI integration test](CLI.md#integration-test) against the packaged plugin on macOS. To verify that both Copy buttons produce working CLI configurations, add `--cli build/ij-format` to the plugin integration command above.

GitHub Actions runs Go checks on Ubuntu, macOS, and Windows, plus a Go 1.22 compatibility job on Ubuntu. The Ubuntu plugin job builds the ZIP, runs Plugin Verifier, and uploads the verified ZIP and any generated verifier reports as workflow artifacts.

## Releasing

Set `pluginVersion` and add matching entries to both [changelogs](CHANGELOG.md). After CI passes on the release commit, push an annotated `vMAJOR.MINOR.PATCH` tag for that commit.

The workflow verifies the tagged source and creates a GitHub Release draft with the verified plugin ZIP, CLI ZIPs for macOS, Linux, and Windows (`amd64` and `arm64`), and `SHA256SUMS`. The draft uses the matching English changelog entry. Review the draft and test the downloaded plugin and CLI together on the target IDEA version before publishing. If the workflow reports that a release already exists, review that release and its attachments on GitHub.

## Reporting issues

Keep each formatting request independent of the active project. Include concrete reproduction steps for formatter changes and use self-contained samples in reports. Remove credentials and private source code from examples or logs.
