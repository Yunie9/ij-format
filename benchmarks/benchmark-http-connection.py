#!/usr/bin/env python3
"""Compare fresh and reused loopback HTTP connections against the stock IDEA plugin."""
import argparse
from collections import defaultdict
from contextlib import closing
from http.client import HTTPConnection
import json
from pathlib import Path
from runpy import run_path
from statistics import median
from time import perf_counter_ns
from urllib.parse import urlsplit

HERE = Path(__file__).resolve().parent
BENCHMARK = run_path(str(HERE / "benchmark-idea-plugin.py"))
INTEGRATION = BENCHMARK["INTEGRATION"]


def call(connection, path, headers, payload):
    start = perf_counter_ns()
    connection.request("POST", path, body=payload, headers=headers)
    response = connection.getresponse()
    body = json.loads(response.read())
    elapsed = (perf_counter_ns() - start) / 1e6
    assert response.status == 200 and "result" in body, body
    return elapsed, body["result"]


def sample(url, headers, requests):
    samples = defaultdict(list)
    with closing(HTTPConnection(url.hostname, url.port, timeout=15)) as reused:
        reused.connect()
        original_socket = reused.sock
        for i, (payload, validate) in enumerate(requests):
            modes = ("fresh", "reused") if i % 2 == 0 else ("reused", "fresh")
            for mode in modes:
                if mode == "fresh":
                    with closing(HTTPConnection(url.hostname, url.port, timeout=15)) as fresh:
                        elapsed, result = call(fresh, url.path, headers, payload)
                else:
                    elapsed, result = call(reused, url.path, headers, payload)
                    assert reused.sock is original_socket, "The server closed the reused connection"
                validate(result)
                samples[mode].append(elapsed)
    return samples


def format_requests(language, start, count):
    for index in range(start, start + count):
        fixture = BENCHMARK["fixture"](language, 1, index)

        def validate(result, item=fixture):
            assert not result.get("isError"), result
            item.check(result["content"][0]["text"])
        yield fixture.payload(), validate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--idea-home", type=Path, required=True)
    parser.add_argument("--archive", type=Path, default=INTEGRATION["default_archive"]())
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    all_samples = defaultdict(list)
    ping = b'{"jsonrpc":"2.0","id":1,"method":"ping"}'
    for run in range(3):
        log = args.out / f"idea-{run + 1}.log"
        with INTEGRATION["run_idea"](args.idea_home.resolve(), log, args.archive.resolve(),
                                    project_first=True) as (state, worker):
            wait = INTEGRATION["wait_for"]
            wait(worker, log, "TEST_PROJECT_INDENT 10")
            wait(worker, state / "logs/idea.log", "ij-format MCP ready")
            client = INTEGRATION["Client"](json.loads((state / "config/ij-format-mcp.json").read_text()))
            client.initialize()
            url = urlsplit(client.url)
            for language in ("java", "kotlin"):
                sample(url, client.headers, format_requests(language, 0, 40))
            for workload, requests in (
                ("ping", [(ping, lambda _: None)] * 100),
                ("java-1k", format_requests("java", 100, 100)),
                ("kotlin-1k", format_requests("kotlin", 100, 100)),
            ):
                for mode, values in sample(url, client.headers, requests).items():
                    all_samples[(workload, mode)].extend(values)
            (state / "finish").touch()
            assert worker.wait(timeout=15) == 0
    results = []
    for (workload, mode), values in all_samples.items():
        values.sort()
        p95 = values[(len(values) * 95 + 99) // 100 - 1]
        results.append({"workload": workload, "mode": mode, "samples": len(values),
                        "p50_ms": round(median(values), 3), "p95_ms": round(p95, 3)})
    report = json.dumps(results, indent=2) + "\n"
    (args.out / "summary.json").write_text(report)
    print(report, end="")


if __name__ == "__main__":
    main()
