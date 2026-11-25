#!/usr/bin/env python3
"""Measure ij-format MCP against a fresh, isolated IDEA process per repetition."""
import argparse
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
import csv
from dataclasses import dataclass
import hashlib
from http.client import HTTPConnection, HTTPException
import json
import math
import os
from pathlib import Path
import platform
from runpy import run_path
import subprocess
from threading import Barrier, Event, Thread
import time
from urllib.parse import urlsplit

INTEGRATION = run_path(str(Path(__file__).resolve().parent.parent / "integration-tests/test-idea-plugin.py"))
TIMINGS = ("http_queue_ms", "edt_queue_ms", "formatter_ms", "edt_ms", "heap_bytes", "executor_rejections")
HEADER = "X-Ij-Format-Benchmark"


@dataclass(frozen=True)
class Fixture:
    language: str
    text: str
    whole_file: bool
    index: int

    def payload(self):
        return json.dumps({
            "jsonrpc": "2.0", "id": self.index, "method": "tools/call",
            "params": {"name": "ij_format_lines", "arguments": {
                "language": self.language, "source": self.text,
                "startLine": 1 if self.whole_file else 2,
                "endLine": self.text.count("\n") if self.whole_file else 2,
            }},
        }).encode()

    def check(self, formatted):
        expected = (
            "  void f00000() {\n    int value = 0;\n  }" if self.language == "java"
            else "  fun f00000() {\n    val value = 0\n  }"
        )
        before, _, after = self.text.split("\n", 2)
        if self.whole_file:
            assert expected in formatted and formatted.endswith("}\n"), "unexpected whole-file output"
        else:
            assert formatted == f"{before}\n{expected}\n{after}", "unexpected edits for line 2"


def fixture(language, kib, index, whole_file=False):
    lines = [f"class Bench{index:06d} {{\n"]
    size = len(lines[0])
    method = 0
    while True:
        if language == "java":
            line = f"  void f{method:05d}(){{int value={method};}}\n\n"
        else:
            line = f"  fun f{method:05d}(){{val value={method}}}\n\n"
        if size + len(line) + len("  // \n}\n") > kib * 1024:
            break
        lines.append(line)
        size += len(line)
        method += 1
    lines.append("  // " + "x" * (kib * 1024 - size - len("  // \n}\n")) + "\n}\n")
    return Fixture(language, "".join(lines), whole_file, index)


def http_request(url, headers, payload, sent=None):
    connection = HTTPConnection(url.hostname, url.port, timeout=20)
    try:
        connection.request("POST", url.path, payload, headers)
        if sent:
            sent.set()
        response = connection.getresponse()
        return response.status, response.getheader(HEADER), response.read()
    finally:
        if sent:
            sent.set()
        connection.close()


def timing_fields(header):
    if header is None:
        return {}
    http_queue, edt_queue, formatter, edt, heap, rejections = map(int, header.split(","))
    return dict(zip(TIMINGS, (http_queue / 1e6, edt_queue / 1e6, formatter / 1e6, edt / 1e6, heap, rejections)))


def measure(url, headers, source, payload, instrumented, sent=None):
    start = time.monotonic_ns()
    timings = {}
    formatted = None
    try:
        status, header, body = http_request(url, headers, payload, sent)
        timings = timing_fields(header)
        if status != 200:
            outcome = f"http_{status}"
        else:
            reply = json.loads(body)
            if "error" in reply:
                outcome = f"rpc_{reply['error']['code']}"
            elif reply["result"].get("isError"):
                outcome = "tool_error: " + reply["result"]["content"][0]["text"]
            else:
                outcome = "ok"
                formatted = reply["result"]["content"][0]["text"]
    except (OSError, HTTPException) as error:
        outcome = type(error).__name__
    duration = (time.monotonic_ns() - start) / 1e6
    if outcome == "ok":
        if instrumented:
            assert 0 < timings["formatter_ms"] <= timings["edt_ms"], timings
        else:
            assert not timings, "stock comparison requires an uninstrumented archive"
        source.check(formatted)
    return {
        "request": source.index, "outcome": outcome, "language": source.language,
        "bytes": len(source.text), "scope": "whole" if source.whole_file else "line_2",
        "e2e_ms": duration, **timings,
    }


def rss_kib(pid):
    return int(subprocess.check_output(["ps", "-o", "rss=", "-p", str(pid)], text=True).strip())


def sample_rss(pid, stop, samples):
    while not stop.wait(0.05):
        samples.append(rss_kib(pid))


def counters_at_ping(url, headers):
    _, header, _ = http_request(url, headers, b'{"jsonrpc":"2.0","id":0,"method":"ping"}')
    return timing_fields(header)


def run_waves(url, headers, waves, instrumented):
    width = len(waves[0])
    rows = []
    with ThreadPoolExecutor(max_workers=width) as workers:
        for wave, sources in enumerate(waves):
            large = [(item, item.payload()) for item in sources if width > 1 and item.whole_file]
            small = [(item, item.payload()) for item in sources if width == 1 or not item.whole_file]
            futures = []
            for item, payload in large:
                sent = Event()
                futures.append(workers.submit(measure, url, headers, item, payload, instrumented, sent))
                if not sent.wait(5):
                    raise RuntimeError("Large request did not start")
            barrier = Barrier(len(small), timeout=10)

            def start_small(item, payload):
                barrier.wait()
                return measure(url, headers, item, payload, instrumented)

            futures.extend(workers.submit(start_small, item, payload) for item, payload in small)
            rows.extend({"wave": wave + 1, **future.result()} for future in futures)
    return rows


def scenario(pid, url, headers, name, waves, run, instrumented):
    before = counters_at_ping(url, headers)
    before_rss = rss_kib(pid)
    samples = [before_rss]
    stop = Event()
    sampler = Thread(target=sample_rss, args=(pid, stop, samples))
    sampler.start()
    start = time.monotonic()
    try:
        rows = run_waves(url, headers, waves, instrumented)
        wall_ms = (time.monotonic() - start) * 1000
    finally:
        stop.set()
        sampler.join()
    after_rss = rss_kib(pid)
    after = counters_at_ping(url, headers)
    before_heap, after_heap = before.get("heap_bytes"), after.get("heap_bytes")
    heap = [row["heap_bytes"] for row in rows if "heap_bytes" in row]
    edt_ms = sum(row.get("edt_ms", 0) for row in rows)
    resources = {
        "run": run, "scenario": name, "wall_ms": wall_ms, "calls": len(rows),
        "ok": sum(row["outcome"] == "ok" for row in rows),
        "rss_before_kib": before_rss, "rss_peak_kib": max(*samples, after_rss),
        "rss_after_kib": after_rss, "rss_samples": len(samples) + 1,
        "heap_before_bytes": before_heap, "heap_peak_observed_bytes": (
            max(*heap, before_heap, after_heap) if instrumented else None
        ), "heap_after_bytes": after_heap,
        "edt_total_ms": edt_ms if instrumented else None,
        "edt_occupancy_pct": 100 * edt_ms / wall_ms if instrumented else None,
        "executor_rejections": after["executor_rejections"] - before["executor_rejections"] if instrumented else None,
    }
    return [{"run": run, "scenario": name, **row} for row in rows], resources


def workloads(reverse=False):
    index = 0

    def build(language, kib=1, whole=False):
        nonlocal index
        index += 1
        return fixture(language, kib, index, whole)

    languages = ("kotlin", "java") if reverse else ("java", "kotlin")
    for language in languages:
        yield f"{language}-first", [[build(language)]]
    for language in languages:
        for kib in (1, 32, 120):
            yield f"{language}-single-line-{kib}k", [[build(language, kib)] for _ in range(12)]
        yield f"{language}-single-whole-120k", [[build(language, 120, True)] for _ in range(8)]
        yield f"{language}-batch-100", [[build(language)] for _ in range(100)]
        for width in (1, 4, 8, 16):
            yield f"{language}-burst-{width}", [[build(language) for _ in range(width)] for _ in range(10)]
        for large, small in ((1, 7), (2, 14)):
            yield f"{language}-mixed-{large}+{small}", [
                [build(language, 120, True) for _ in range(large)] + [build(language) for _ in range(small)]
                for _ in range(8)
            ]
    yield "java-kotlin-mixed-2+14", [
        [build("java", 120, True), build("kotlin", 120, True)] +
        [build(languages[i % 2]) for i in range(14)] for _ in range(8)
    ]


def summary(rows):
    groups = defaultdict(list)
    for row in rows:
        groups[(row["scenario"], row["language"], row["scope"])].append(row)
    summaries = []
    for (scenario_name, language, scope), group in groups.items():
        entries = {"scenario": scenario_name, "language": language, "scope": scope, "calls": len(group)}
        counts = Counter(row["outcome"] for row in group)
        entries.update(ok=counts["ok"], failures=json.dumps({k: v for k, v in counts.items() if k != "ok"}))
        for metric in ("http_queue_ms", "edt_queue_ms", "formatter_ms", "edt_ms", "e2e_ms"):
            values = sorted(row[metric] for row in group if row["outcome"] == "ok" and metric in row)
            for percentile in (50, 95):
                entries[f"{metric}_p{percentile}"] = round(
                    values[math.ceil(len(values) * percentile / 100) - 1], 3
                ) if values else None
        summaries.append(entries)
    return summaries


def write_csv(path, rows):
    columns = list(dict.fromkeys(key for row in rows for key in row))
    with path.open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--idea-home", type=Path, required=True)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--stock", action="store_true", help="compare end-to-end latency against the unmodified ZIP")
    parser.add_argument("--gui", action="store_true", help="run with a visible project window")
    args = parser.parse_args()
    if args.repeats < 1:
        parser.error("--repeats must be positive")
    args.out.mkdir(parents=True, exist_ok=True)
    requests = []
    resources = []
    idea = args.idea_home.resolve()
    for run in range(1, args.repeats + 1):
        log = args.out / f"ide-{run}.log"
        with INTEGRATION["run_idea"](idea, log, args.archive.resolve(),
                                    welcome=args.gui, project_first=True) as (state, worker):
            wait = INTEGRATION["wait_for"]
            if args.gui:
                wait(worker, state / "logs/idea.log", f"Opening existing project with .idea at {state / 'project'}")
            else:
                wait(worker, log, "TEST_PROJECT_INDENT 10")
            wait(worker, state / "logs/idea.log", "ij-format MCP ready")
            if args.gui:
                assert "TEST_WELCOME_OPEN_PROJECTS" not in log.read_text(errors="replace"), "Unexpected Welcome screen"
            client = INTEGRATION["Client"](json.loads((state / "config/ij-format-mcp.json").read_text()))
            client.initialize()
            url = urlsplit(client.url)
            headers = client.headers
            for name, waves in workloads(reverse=run % 2 == 0):
                rows, usage = scenario(worker.pid, url, headers, name, waves, run, not args.stock)
                requests.extend(rows)
                resources.append(usage)
                print(f"{run} {name}: {usage['ok']}/{usage['calls']} completed", flush=True)
                if not usage["ok"] or (len(waves[0]) == 1 and usage["ok"] != usage["calls"]):
                    write_csv(args.out / "requests.csv", requests)
                    write_csv(args.out / "resources.csv", resources)
                    raise RuntimeError(f"Unexpected failure: {Counter(row['outcome'] for row in rows)}")
            if not args.gui:
                (state / "finish").touch()
                assert worker.wait(timeout=15) == 0
        write_csv(args.out / "requests.csv", requests)
        write_csv(args.out / "resources.csv", resources)
        write_csv(args.out / "summary.csv", summary(requests))
    (args.out / "environment.json").write_text(json.dumps({
        "host": platform.platform(), "cpu_count": os.cpu_count(),
        "idea_build": (idea / "Resources/build.txt").read_text().strip(),
        "archive": args.archive.name, "archive_sha256": hashlib.sha256(args.archive.read_bytes()).hexdigest(),
        "instrumented": not args.stock, "gui": args.gui, "repeats": args.repeats,
    }, indent=2) + "\n")


if __name__ == "__main__":
    main()
