#!/usr/bin/env python3
"""Exercise the packaged HTTP plugin in isolated IDEA directories."""
import argparse
from contextlib import contextmanager, ExitStack
from http.client import HTTPConnection
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent.parent


def default_archive():
    version = next(line.removeprefix("pluginVersion=") for line in
                   (ROOT / "idea-plugin/gradle.properties").read_text().splitlines() if line.startswith("pluginVersion="))
    return ROOT / f"idea-plugin/build/distributions/ij-format-mcp-{version}.zip"


def scheme(name, width):
    settings = "".join(
        f'<codeStyleSettings language="{lang}"><indentOptions>'
        f'<option name="INDENT_SIZE" value="{width}" />'
        f'</indentOptions></codeStyleSettings>'
        for lang in ("JAVA", "kotlin")
    )
    return (
        f'<code_scheme name="{name}" version="173"><option name="OTHER_INDENT_OPTIONS">'
        f'<value><option name="INDENT_SIZE" value="{width}" /></value></option>{settings}</code_scheme>'
    )


def configure(state):
    config = state / "config"
    project = state / "project"
    for directory in ("options", "codestyles"):
        (config / directory).mkdir(parents=True, exist_ok=True)
    (config / "options/code.style.schemes.xml").write_text(
        '<application><component name="CodeStyleSchemeSettings">'
        '<option name="CURRENT_SCHEME_NAME" value="McpTest" /></component></application>'
    )
    (config / "codestyles/McpTest.xml").write_text(scheme("McpTest", 2))
    (config / "codestyles/McpSecond.xml").write_text(scheme("McpSecond", 6))
    (project / ".idea").mkdir(parents=True)
    (project / ".editorconfig").write_text("root = true\n[*]\nindent_style = space\nindent_size = 12\n")


def launch_args(idea, state, *, welcome=False, project_first=False):
    product = json.loads((idea / "Resources/product-info.json").read_text())
    launch = product["launch"][0]
    java = (idea / "Resources" / launch["javaExecutablePath"]).resolve()
    classpath = os.pathsep.join(str(idea / "lib" / jar) for jar in launch["bootClassPathJarNames"])
    flags = [flag.replace("$APP_PACKAGE", str(idea.parent)) for flag in launch["additionalJvmArguments"]]
    folders = {
        "idea.config.path": "config", "idea.system.path": "system", "idea.plugins.path": "plugins",
        "idea.log.path": "logs", "java.io.tmpdir": "tmp", "user.home": "home",
    }
    for directory in folders.values():
        (state / directory).mkdir(exist_ok=True)
    local_map = state / "regions.json"
    local_map.write_text("[]")
    settings = [
        f"-Didea.home.path={idea}", "-Didea.initially.ask.config=false",
        "-Dide.no.platform.update=true", "-Dide.no.plugin.update=true",
        "-Didea.builtin.server.disabled=true", "-Didea.trust.all.projects=true",
        "-Dijformat.mcp.port=0",
        "-Didea.load.plugins.id=org.ijformat.mcp,org.ijformat.test,org.jetbrains.kotlin,"
        "org.editorconfig.editorconfigjetbrains",
    ]
    settings.extend(f"-D{key}={state / directory}" for key, directory in folders.items())
    settings.extend(f"-Djb.mapper.configuration.url{suffix}={local_map.as_uri()}"
                    for suffix in ("", ".africa", ".americas", ".apac", ".china", ".europe", ".middle_east", ".oceania"))
    args = []
    if welcome:
        settings.append("-Djb.consents.confirmation.enabled=false")
        if project_first:
            settings.append("-Dide.experimental.ui.onboarding=false")
            args.append(str(state / "project"))
    else:
        settings.extend(("-Djava.awt.headless=true", "-Didea.suppress.first.window=true"))
        args.extend(("ij-format-verify", str(state)))
        if project_first:
            args.append("project-first")
    return [str(java), "-Xms128m", "-Xmx1400m", *flags, *settings, "-cp", classpath, "com.intellij.idea.Main", *args]


class Client:
    def __init__(self, settings):
        self.url = settings["url"]
        self.headers = dict(settings["headers"], **{
            "Content-Type": "application/json", "Accept": "application/json, text/event-stream",
        })
        self.request_id = 0

    def rpc(self, method, params=None):
        body = {"jsonrpc": "2.0", "method": method}
        if not method.startswith("notifications/"):
            self.request_id += 1
            body["id"] = self.request_id
        if params is not None:
            body["params"] = params
        request = Request(self.url, json.dumps(body).encode(), self.headers)
        with urlopen(request, timeout=15) as response:
            if session := response.headers.get("Mcp-Session-Id"):
                self.headers["Mcp-Session-Id"] = session
            if response.status == 202:
                return None
            if response.headers.get_content_type() == "text/event-stream":
                for line in response:
                    if line.startswith(b"data: "):
                        event = json.loads(line[6:])
                        if event.get("id") == body.get("id"):
                            return event
                raise AssertionError(f"Missing response for {method}")
            return json.load(response)

    def initialize(self):
        result = self.rpc("initialize", {
            "protocolVersion": "2025-06-18", "capabilities": {},
            "clientInfo": {"name": "ij-format-integration", "version": "1"},
        })["result"]
        self.headers["MCP-Protocol-Version"] = result["protocolVersion"]
        self.rpc("notifications/initialized")


def install_test_starter(idea, state):
    output = state / "test-classes"
    output.mkdir()
    java = idea / "jbr/Contents/Home/bin/javac"
    subprocess.run([str(java), "-proc:none", "-cp", str(idea / "lib/*"), "-d", str(output),
                    str(ROOT / "idea-plugin/src/test/java/org/ijformat/test/VerificationStarter.java")], check=True)
    plugin = state / "plugins/ij-format-test/lib"
    plugin.mkdir(parents=True)
    with ZipFile(plugin / "verification.jar", "w") as jar:
        jar.writestr("META-INF/plugin.xml", """
            <idea-plugin>
              <id>org.ijformat.test</id>
              <name>ij-format integration starter</name>
              <vendor>ij-format</vendor>
              <depends>com.intellij.modules.platform</depends>
              <depends>org.ijformat.mcp</depends>
              <applicationListeners>
                <listener class="org.ijformat.test.VerificationStarter$WelcomeProbe"
                          topic="com.intellij.ide.AppLifecycleListener" activeInHeadlessMode="false"/>
              </applicationListeners>
              <extensions defaultExtensionNs="com.intellij">
                <appStarter id="ij-format-verify" implementation="org.ijformat.test.VerificationStarter"/>
              </extensions>
            </idea-plugin>
        """)
        for file in output.rglob("*.class"):
            jar.write(file, file.relative_to(output))


@contextmanager
def run_idea(idea, log_path, plugin_archive, **launch_mode):
    with tempfile.TemporaryDirectory(prefix="ijfm-", dir="/tmp") as folder:
        state = Path(folder)
        configure(state)
        with ZipFile(plugin_archive) as archive:
            archive.extractall(state / "plugins")
        install_test_starter(idea, state)
        with log_path.open("w") as log:
            worker = subprocess.Popen(launch_args(idea, state, **launch_mode), cwd=state, stdout=log,
                                      stderr=subprocess.STDOUT)
            try:
                yield state, worker
            finally:
                if worker.poll() is None:
                    worker.terminate()
                    try:
                        worker.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        worker.kill()
                        worker.wait()


def verify_live_limits(state, worker, log_path, client):
    config = state / "config/ij-format-mcp.json"
    connection_file = config.read_bytes()
    persisted = state / "config/options/ij-format-mcp.xml"
    address = urlsplit(client.url)
    requests = []
    (state / "occupy-workers").touch()
    wait_for(worker, log_path, "TEST_MCP_EDT_HELD")
    with ExitStack() as stack:
        for request_id in range(1, 8):
            if request_id == 5:
                wait_for(worker, log_path, "TEST_MCP_WORKERS_BUSY")
            language, line, expected_line = (
                ("java", "int value=1;", "int value = 1;") if request_id % 2
                else ("kotlin", "val value=1", "val value = 1")
            )
            source = f"class Demo {{\n  {line}\n}}\n"
            body = json.dumps({"jsonrpc": "2.0", "id": request_id, "method": "tools/call", "params": {
                "name": "ij_format_lines", "arguments": {
                    "language": language, "source": source, "startLine": 2, "endLine": 2,
                },
            }})
            connection = HTTPConnection(address.hostname, address.port, timeout=15)
            stack.callback(connection.close)
            connection.request("POST", address.path, body, client.headers)
            requests.append((connection, connection.sock, source.replace(line, expected_line)))

        wait_for(worker, log_path, "TEST_MCP_LIMITS_SAVED")
        for name, value in (("minWorkers", 1), ("maxWorkers", 2), ("queueCapacity", 1)):
            wait_for(worker, persisted, f'name="{name}" value="{value}"')
        for request_id, (connection, socket, expected) in enumerate(requests, 1):
            with connection.getresponse() as response:
                reply = json.load(response)
                assert response.status == 200 and reply["id"] == request_id, reply
                result = reply["result"]
                assert not result["isError"] and result["content"][0]["text"] == expected, result
            assert connection.sock is socket

        (state / "expand-limits").touch()
        wait_for(worker, log_path, "TEST_MCP_LIMITS_EXPANDED")
        connection, socket, _ = requests[0]
        connection.request("POST", address.path, '{"jsonrpc":"2.0","id":8,"method":"ping"}', client.headers)
        with connection.getresponse() as response:
            assert response.status == 200 and json.load(response)["id"] == 8
        assert connection.sock is socket
        assert config.read_bytes() == connection_file
    print("PASS Apply shrinks with 4 running and 3 queued Java/Kotlin requests; all complete, "
          "then live expansion preserves the same TCP connection and credentials", flush=True)


def test(idea, out, plugin_archive, cli=None):
    log_path = out / "integration.log"
    with run_idea(idea, log_path, plugin_archive) as (state, worker):
        wait_for(worker, log_path, "TEST_OPEN_PROJECTS 0")
        config = state / "config/ij-format-mcp.json"
        wait_for(worker, state / "logs/idea.log", "ij-format MCP ready")
        assert config.stat().st_mode & 0o777 == 0o600
        client = Client(json.loads(config.read_text()))
        client.initialize()
        verify_access(client)
        exercise(client, 2, "headless welcome event with no open project")
        verify_live_limits(state, worker, log_path, client)
        persisted = state / "config/options/ij-format-mcp.xml"
        assert client.rpc("tools/list")["result"]["tools"]
        (state / "disable-mcp").touch()
        wait_for(worker, log_path, "TEST_MCP_DISABLED")
        wait_for(worker, persisted, 'name="enabled" value="false"')
        try:
            client.rpc("tools/list")
            raise AssertionError("The disabled MCP endpoint accepted a request")
        except URLError as error:
            assert isinstance(error.reason, ConnectionRefusedError), error
        (state / "enable-mcp").touch()
        wait_for(worker, log_path, "TEST_MCP_REENABLED")
        reenabled = json.loads(config.read_text())
        assert reenabled["headers"]["Authorization"] == client.headers["Authorization"]
        copied = json.loads((state / "copied.json").read_text())
        assert copied == {
            "mcpServers": {"ij-format": reenabled},
        }
        copied_path = Path((state / "copied-path.txt").read_text())
        assert copied_path == config
        client = Client(copied["mcpServers"]["ij-format"])
        client.initialize()
        if cli:
            for path in (copied_path, state / "copied.json"):
                checked = subprocess.run([str(cli), "status", "--config", str(path)],
                                         capture_output=True, check=True, text=True, timeout=25)
                assert reenabled["url"] in checked.stdout and "--range" in checked.stdout
                assert reenabled["headers"]["Authorization"] not in checked.stdout + checked.stderr
            java = subprocess.run(
                [str(cli), "-", "--language", "java", "--lines", "2:2", "--config", str(state / "copied.json")],
                input="class Demo {\n  int n=1;\n}\n", capture_output=True, check=True, text=True, timeout=25,
            )
            assert java.stdout == "class Demo {\n  int n = 1;\n}\n"
            kotlin = subprocess.run(
                [str(cli), "-", "--language", "kotlin", "--lines", "2:2", "--config", str(copied_path)],
                input="class Demo {\n  val n=1\n}\n", capture_output=True, check=True, text=True, timeout=25,
            )
            assert kotlin.stdout == "class Demo {\n  val n = 1\n}\n"
            print("PASS both settings copy actions, CLI status, Java using copied MCP JSON, Kotlin using the copied path", flush=True)
        print("PASS application settings validation and persistence, active limits after restart "
              "and Copy MCP JSON", flush=True)
        (state / "open-project").touch()
        wait_for(worker, log_path, "TEST_PROJECT_INDENT 10")
        exercise(client, 2, "open project with 10-space scheme and EditorConfig")
        (state / "switch-scheme").touch()
        wait_for(worker, log_path, "TEST_SCHEME_CHANGED")
        exercise(client, 6, "same MCP connection after global scheme change")
        (state / "finish").touch()
        assert worker.wait(timeout=15) == 0


def test_startup(idea, out, plugin_archive, *, welcome):
    scenario = "welcome" if welcome else "project-first"
    log_path = out / f"{scenario}.log"
    with run_idea(idea, log_path, plugin_archive, welcome=welcome, project_first=not welcome) as (state, worker):
        marker = "TEST_WELCOME_OPEN_PROJECTS 0" if welcome else "TEST_PROJECT_INDENT 10"
        wait_for(worker, log_path, marker)
        wait_for(worker, state / "logs/idea.log", "ij-format MCP ready")
        client = Client(json.loads((state / "config/ij-format-mcp.json").read_text()))
        client.initialize()
        context = "IDEA Welcome screen with zero open projects" if welcome else "project opened without a welcome event"
        exercise(client, 2, context)
        if not welcome:
            (state / "finish").touch()
            assert worker.wait(timeout=15) == 0


def wait_for(worker, log_path, expected):
    until = time.monotonic() + 45
    while time.monotonic() < until and worker.poll() is None:
        if log_path.exists() and expected in log_path.read_text(errors="replace"):
            return
        time.sleep(0.1)
    raise RuntimeError(f"Missing {expected!r}. See {log_path}")


def verify_access(client):
    for remove_token, additional, status in [
        (True, {}, 401),
        (False, {"Origin": "https://example.invalid"}, 403),
        (False, {"Host": "example.invalid"}, 403),
    ]:
        headers = dict(client.headers, **additional)
        if remove_token:
            headers.pop("Authorization")
        body = b'{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
        try:
            with urlopen(Request(client.url, body, headers), timeout=5):
                raise AssertionError(f"Expected HTTP {status}")
        except HTTPError as error:
            assert error.code == status, error
    modern = dict(client.headers, **{"MCP-Protocol-Version": "2026-07-28"})
    body = b'{"jsonrpc":"2.0","id":1,"method":"server/discover"}'
    try:
        with urlopen(Request(client.url, body, modern), timeout=5):
            raise AssertionError("Expected a protocol-version error")
    except HTTPError as error:
        reply = json.load(error)
        assert error.code == 400 and reply["error"]["code"] == -32022, reply
        assert "2025-11-25" in reply["error"]["data"]["supported"], reply
    print("PASS protected local HTTP endpoint, 0600 config and supported protocol negotiation", flush=True)


def exercise(client, width, context):
    tools = client.rpc("tools/list")["result"]["tools"]
    assert [tool["name"] for tool in tools] == ["ij_format_lines"], tools
    tool = tools[0]
    assert {"source", "language", "startLine", "endLine"} <= set(tool["inputSchema"]["properties"]), tool

    def format_source(language, source, start=2, end=2):
        reply = client.rpc("tools/call", {"name": "ij_format_lines", "arguments": {
            "language": language, "source": source, "startLine": start, "endLine": end,
        }})
        assert "result" in reply, reply
        result = reply["result"]
        text = "".join(item["text"] for item in result["content"] if item["type"] == "text")
        return result, text

    java = "class Demo {\n    void f(){int value=1;}\n}\n"
    kotlin = "class Demo {\n    fun f(){val value=1}\n}\n"
    indent = " " * width
    for lang, source, expected in [
        ("java", java, f"class Demo {{\n{indent}void f() {{\n{indent * 2}int value = 1;\n{indent}}}\n}}\n"),
        ("kotlin", kotlin, f"class Demo {{\n{indent}fun f() {{\n{indent * 2}val value = 1\n{indent}}}\n}}\n"),
    ]:
        result, text = format_source(lang, source)
        assert not result.get("isError") and text == expected, (lang, width, result, text, expected)
        result, text = format_source(lang, source.replace("\n", "\r\n"))
        assert not result.get("isError") and text == expected.replace("\n", "\r\n"), (lang, width, result)
    result, text = format_source("java", java, 2, 99)
    assert result["isError"] and "exceeds source" in text, result
    for lang, source, line in [
        ("java", "class A { \n  void f(){\n // test     \n   int a=3;\n  }\n}\n", 2),
        ("kotlin", "class A {\n    fun f() {\n\n       val a=1\n\n    }\n}", 3),
    ]:
        result, text = format_source(lang, source, line, line)
        assert result["isError"] and "outside the selected lines" in text, (lang, width, result)
    print(f"PASS Java/Kotlin {width}-space global scheme, {context}, invalid range", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--idea-home", type=Path, required=True)
    parser.add_argument("--archive", type=Path, default=default_archive())
    parser.add_argument("--cli", type=Path, help="verify the copied settings with this CLI binary")
    parser.add_argument("--out", type=Path, default=ROOT / "idea-plugin/build/integration")
    parser.add_argument("--welcome", action="store_true", help="also start an isolated graphical IDEA at its Welcome screen")
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    test(args.idea_home.resolve(), args.out.resolve(), args.archive.resolve(), args.cli.resolve() if args.cli else None)
    test_startup(args.idea_home.resolve(), args.out.resolve(), args.archive.resolve(), welcome=False)
    if args.welcome:
        test_startup(args.idea_home.resolve(), args.out.resolve(), args.archive.resolve(), welcome=True)


if __name__ == "__main__":
    main()
