#!/usr/bin/env python3
"""Exercise the CLI against an unmodified release ZIP in isolated IDEA."""
import argparse
from functools import partial
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import os
from pathlib import Path
from runpy import run_path
import socket
import subprocess
import tempfile
from threading import Thread
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent.parent
HOST = run_path(str(Path(__file__).with_name("test-idea-plugin.py")))
CLI = ROOT / "build/ij-format"
JAVA = b"class Demo {\n  void f(){int x=1;}\n}\n"
JAVA_FORMATTED = b"class Demo {\n  void f() {\n    int x = 1;\n  }\n}\n"
KOTLIN = b"class Demo {\n  fun f(){val x=1}\n}\n"
KOTLIN_FORMATTED = b"class Demo {\n  fun f() {\n    val x = 1\n  }\n}\n"


def invoke(cli, config, *arguments, input_bytes=None, code=0):
    result = subprocess.run(
        [str(cli), "--config", str(config), *map(str, arguments)],
        input=input_bytes, capture_output=True, timeout=35,
    )
    assert result.returncode == code, (arguments, result)
    if code != 2:
        assert not result.stderr, result.stderr
    return result


class RecordingProxy(HTTPServer):
    def __init__(self, backend):
        super().__init__(("127.0.0.1", 0), ForwardRequest)
        self.backend = backend
        self.accepts = 0
        self.methods = []
        self.backend_sockets = []

    def get_request(self):
        connection, address = super().get_request()
        self.accepts += 1
        return connection, address


class ForwardRequest(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def setup(self):
        super().setup()
        address = self.server.backend
        self.backend = HTTPConnection(address.hostname, address.port, timeout=20)

    def finish(self):
        self.backend.close()
        super().finish()

    def do_POST(self):
        payload = self.rfile.read(int(self.headers["Content-Length"]))
        self.server.methods.append(json.loads(payload)["method"])
        headers = {name: value for name, value in self.headers.items()
                   if name.lower() not in ("host", "connection")}
        self.backend.request("POST", self.path, payload, headers)
        if not self.server.backend_sockets or self.server.backend_sockets[-1] is not self.backend.sock:
            self.server.backend_sockets.append(self.backend.sock)
        upstream = self.backend.getresponse()
        response = upstream.read()
        self.send_response(upstream.status)
        if media := upstream.getheader("Content-Type"):
            self.send_header("Content-Type", media)
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        self.wfile.write(response)

    def log_message(self, *_):
        pass


def check_single(invoke, config, workspace):
    java = workspace / "Demo.java"
    java.write_bytes(JAVA)
    assert invoke(config, java, "--lines", "2:2").stdout == JAVA_FORMATTED
    assert java.read_bytes() == JAVA
    crlf = lambda data: data.replace(b"\n", b"\r\n")
    assert invoke(config, "-", "--language", "kotlin", "--lines", "2:2",
                  input_bytes=crlf(KOTLIN)).stdout == crlf(KOTLIN_FORMATTED)
    result = invoke(config, java, "--lines", "2:2", "--diff")
    assert result.stdout.startswith(b"--- ") and b"+    int x = 1;" in result.stdout
    assert java.read_bytes() == JAVA
    assert invoke(config, java, "--lines", "2:2", "--check", code=1).stdout == str(java).encode() + b"\n"
    java.chmod(0o640)
    assert invoke(config, java, "--lines", "2:2", "--write").stdout == b""
    assert java.read_bytes() == JAVA_FORMATTED and (java.stat().st_mode & 0o777) == 0o640
    assert invoke(config, java, "--lines", "2:2", "--check").stdout == b""
    assert invoke(config, java, "--lines", "2:9", code=2).stderr
    assert invoke(config, "-", "--lines", "1:1", input_bytes=KOTLIN, code=2).stderr
    assert invoke(config, "-", "--language", "java", "--lines", "1:1", "--write",
                  input_bytes=JAVA, code=2).stderr
    assert invoke(config, java, "--lines", "2:2", "--check", "--diff", code=2).stderr
    assert invoke(config, "-", "--language", "java", "--whole-file", input_bytes=b"").stdout == b""
    assert b"outside the selected lines" in invoke(
        config, "-", "--language", "java", "--lines", "2:2", code=2,
        input_bytes=b"class A { \n  void f(){\n // test     \n   int a=3;\n  }\n}\n",
    ).stderr
    print("PASS Java path, Kotlin CRLF stdin, range guard, diff, check and atomic write", flush=True)
    return java


def check_batch(invoke, config, workspace, java):
    kotlin = workspace / "Demo.kt"
    kotlin.write_bytes(KOTLIN)
    missing = workspace / "Missing.java"
    failure = invoke(config, kotlin, missing, java, "--whole-file", "--check", code=2)
    assert str(kotlin).encode() in failure.stdout and str(missing).encode() in failure.stderr
    assert kotlin.read_bytes() == KOTLIN
    invoke(config, kotlin, java, "--whole-file", "--write")
    assert kotlin.read_bytes() == KOTLIN_FORMATTED and java.read_bytes() == JAVA_FORMATTED
    invoke(config, kotlin, java, "--whole-file", "--check")

    settings = json.loads(config.read_text())
    proxy_config = workspace / "proxy.json"
    with RecordingProxy(urlsplit(settings["url"])) as proxy:
        settings["url"] = f"http://127.0.0.1:{proxy.server_port}/mcp"
        proxy_config.write_text(json.dumps(settings))
        listener = Thread(target=proxy.serve_forever, daemon=True)
        listener.start()
        try:
            batch = []
            for index in range(100):
                path = workspace / f"Sample{index:03d}{'.java' if index % 2 == 0 else '.kt'}"
                path.write_bytes(JAVA if index % 2 == 0 else KOTLIN)
                batch.append(path)
            result = invoke(proxy_config, *batch, "--whole-file", "--check", code=1)
            assert result.stdout.splitlines() == [str(path).encode() for path in batch]
            assert proxy.methods == ["initialize", "notifications/initialized"] + ["tools/call"] * 100
            assert proxy.accepts == len(proxy.backend_sockets) == 1
        finally:
            proxy.shutdown()
            listener.join()
    print("PASS mixed-language batch and error precedence; 100 files, one handshake, one TCP connection", flush=True)


def check_connection_errors(invoke, config, workspace, java):
    settings = json.loads(config.read_text())
    settings["headers"]["Authorization"] = "Bearer invalidToken"
    wrong = workspace / "wrong.json"
    wrong.write_text(json.dumps(settings))
    assert b"HTTP 401" in invoke(wrong, java, "--whole-file", code=2).stderr
    with socket.socket() as reserved:
        reserved.bind(("127.0.0.1", 0))
        settings["url"] = f"http://127.0.0.1:{reserved.getsockname()[1]}/mcp"
        wrong.write_text(json.dumps(settings))
        assert b"not reachable" in invoke(wrong, java, "--whole-file", code=2).stderr
    print("PASS exit 2 on authentication failure and unavailable IDEA", flush=True)


def check_discovery(cli, config, workspace, exact):
    environment = dict(os.environ, HOME=str(workspace))
    settings = json.loads(config.read_text())
    token = settings["headers"]["Authorization"].encode()

    def status(*arguments, code=0):
        response = subprocess.run([str(cli), "status", *map(str, arguments)], env=environment,
                                  capture_output=True, timeout=25)
        assert response.returncode == code, response
        assert token not in response.stdout + response.stderr
        return response

    assert b"Settings > Tools > ij-format" in status(code=2).stderr
    parent = workspace / "Library/Application Support/JetBrains"
    instance = parent / "IntelliJIdea2026.1/ij-format-mcp.json"
    instance.parent.mkdir(parents=True)
    instance.write_bytes(config.read_bytes())
    report = status().stdout
    assert settings["url"].encode() in report and b"--lines" in report
    assert (b"--range" in report) == exact
    direct = subprocess.run([str(cli), "-", "--language", "java", "--lines", "2:2"], env=environment,
                            input=JAVA, capture_output=True, check=True, timeout=25)
    assert direct.stdout == JAVA_FORMATTED

    other = parent / "IdeaIC2026.1/ij-format-mcp.json"
    other.parent.mkdir()
    other.write_bytes(config.read_bytes())
    message = status(code=2).stderr
    assert b"--config" in message and os.fsencode(instance) in message and os.fsencode(other) in message
    assert status("--config", instance).stdout == report
    print("PASS first-run diagnostics, automatic discovery, status, and explicit selection across two IDEA profiles",
          flush=True)


def check_exact_ranges(invoke, config, workspace, supported):
    java = workspace / "Exact.java"
    source = 'class Demo {\n  String s = "😀\t"+1;\n  int y=2;\n}\n'
    java.write_bytes(source.encode())
    if not supported:
        failure = invoke(config, java, "--range", "2:18-2:20", code=2)
        assert b"does not support --range" in failure.stderr
        print("PASS exact-range capability error on unmodified 0.2.0", flush=True)
        return

    expected = source.replace('"+1;', '" + 1;').encode()
    assert invoke(config, java, "--range", "2:18-2:20").stdout == expected
    assert invoke(config, java, "--range", "2:18-2:20", "--check", code=1).stdout
    assert b" + 1;" in invoke(config, java, "--range", "2:18-2:20", "--diff").stdout
    invoke(config, java, "--range", "2:18-2:20", "--write")
    assert java.read_bytes() == expected
    assert b"Column 300 exceeds line 2" in invoke(config, java, "--range", "2:300-3:2", code=2).stderr
    assert b"Column 2 exceeds line 5" in invoke(config, java, "--range", "5:2-6:1", code=2).stderr
    assert invoke(config, java, "--range", "3:4-3:4", code=2).stderr
    assert invoke(config, java, "--range", "2:18-2:20", "--lines", "2:2", code=2).stderr
    assert b"outside the selected range" in invoke(
        config, "-", "--language", "java", "--range", "2:1-2:12", code=2,
        input_bytes=b"class A { \n  void f(){\n // test     \n   int a=3;\n  }\n}\n",
    ).stderr

    kotlin = 'class Demo {\r\n  val s = "😀\t"+1\r\n  val y=2\r\n}\r\n'
    assert invoke(config, "-", "--language", "kotlin", "--range", "2:15-2:17",
                  input_bytes=kotlin.encode()).stdout == kotlin.replace('"+1', '" + 1').encode()
    multi = b"class Demo {\n  void f() {\n    int x=1;\n    int y=2;\n  }\n}\n"
    assert invoke(config, "-", "--language", "java", "--range", "3:5-4:13",
                  input_bytes=multi).stdout == multi.replace(b"x=1", b"x = 1").replace(b"y=2", b"y = 2")
    assert invoke(config, "-", "--language", "java", "--range", "5:1-6:1",
                  input_bytes=JAVA_FORMATTED).stdout == JAVA_FORMATTED

    client = HOST["Client"](json.loads(config.read_text()))
    client.initialize()
    mixed = client.rpc("tools/call", {"name": "ij_format_lines", "arguments": {
        "source": source, "language": "java", "startLine": 2,
        "range": {"startLine": 2, "startColumn": 18, "endLine": 2, "endColumn": 20},
    }})
    assert mixed["error"]["code"] == -32602
    print("PASS Java/Kotlin exact ranges, Unicode, CRLF, multiline and range guard", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cli", type=Path, default=CLI)
    parser.add_argument("--idea-home", type=Path, required=True)
    parser.add_argument("--archive", type=Path, default=HOST["default_archive"]())
    parser.add_argument("--out", type=Path, default=ROOT / "idea-plugin/build/cli-integration")
    parser.add_argument("--exact-range", action=argparse.BooleanOptionalAction, default=True,
                        help="expect exact ranges (default); use --no-exact-range with the original 0.2.0 ZIP")
    args = parser.parse_args()
    if not args.cli.is_file():
        parser.error(f"CLI binary not found at {args.cli}. Build it as described in docs/CLI.md.")
    run_cli = partial(invoke, args.cli.resolve())
    args.out.mkdir(parents=True, exist_ok=True)
    log = args.out / "idea-project.log"
    with HOST["run_idea"](args.idea_home.resolve(), log, args.archive.resolve(), project_first=True) as (state, ide):
        HOST["wait_for"](ide, log, "TEST_PROJECT_INDENT 10")
        HOST["wait_for"](ide, state / "logs/idea.log", "ij-format MCP ready")
        config = state / "config/ij-format-mcp.json"
        with tempfile.TemporaryDirectory(prefix="ijf-cli-") as folder:
            workspace = Path(folder)
            java = check_single(run_cli, config, workspace)
            check_batch(run_cli, config, workspace, java)
            check_connection_errors(run_cli, config, workspace, java)
            check_exact_ranges(run_cli, config, workspace, args.exact_range)
            check_discovery(args.cli.resolve(), config, workspace, args.exact_range)
            status = subprocess.check_output([args.cli.resolve(), "status", "--config", config])
            if b"Request timeout: --timeout" in status:
                result = run_cli(config, java, "--lines", "2:2", "--timeout", "3s")
                assert result.stdout == JAVA_FORMATTED
                print("PASS CLI request timeout override", flush=True)
        (state / "finish").touch()
        assert ide.wait(timeout=15) == 0

    log = args.out / "idea-welcome.log"
    with HOST["run_idea"](args.idea_home.resolve(), log, args.archive.resolve(), welcome=True) as (state, ide):
        HOST["wait_for"](ide, log, "TEST_WELCOME_OPEN_PROJECTS 0")
        HOST["wait_for"](ide, state / "logs/idea.log", "ij-format MCP ready")
        config = state / "config/ij-format-mcp.json"
        assert run_cli(config, "-", "--language", "kotlin", "--lines", "2:2",
                      input_bytes=KOTLIN).stdout == KOTLIN_FORMATTED
        if args.exact_range:
            source = b"class Demo {\n  fun f() {\n    val x=1\n  }\n}\n"
            assert run_cli(config, "-", "--language", "kotlin", "--range", "3:5-3:12",
                          input_bytes=source).stdout == source.replace(b"x=1", b"x = 1")
        print("PASS Kotlin CLI on the real Welcome screen with zero open projects", flush=True)


if __name__ == "__main__":
    main()
