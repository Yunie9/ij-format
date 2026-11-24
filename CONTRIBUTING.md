# Contributing

Use JDK 21 and the checked-in Gradle Wrapper.

```sh
./gradlew :idea-plugin:buildPlugin :idea-plugin:compileTestJava :idea-plugin:verifyPlugin
```

Gradle resolves IntelliJ IDEA 2026.1.1 by default. To use a local installation, pass `-PideaHome`. The reports are under `idea-plugin/build/reports/pluginVerifier/`.

```sh
./gradlew :idea-plugin:buildPlugin :idea-plugin:verifyPlugin \
  '-PideaHome=/Applications/IntelliJ IDEA.app'
python3 scripts/test-idea-plugin.py --welcome \
  --idea-home '/Applications/IntelliJ IDEA.app/Contents'
```

The macOS integration script requires Python 3.9 or later. It runs the packaged plugin in isolated IDEA processes with temporary configuration and an ephemeral loopback port. Pass `--welcome` from a graphical login session to include the graphical Welcome screen. Reports and test logs remain under `idea-plugin/build/` by default. The separately compiled test starter is loaded only by the integration script.

- `idea-plugin/src/main/kotlin/` contains startup, settings, HTTP, MCP and formatter code.
- `idea-plugin/src/main/resources/META-INF/` contains the plugin descriptor and icon.
- `idea-plugin/src/test/java/` and `scripts/test-idea-plugin.py` implement the integration scenarios.

Keep each formatting request independent of the active project. Include concrete reproduction steps for formatter changes and use self-contained samples in reports. Remove credentials and private source code from examples or logs.
