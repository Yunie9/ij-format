package main

import (
	"strings"
	"testing"
	"time"
)

func TestCommandHelp(t *testing.T) {
	for _, args := range [][]string{nil, {"--help"}, {"-h"}, {"format", "--help"}} {
		var stdout, stderr strings.Builder
		code := run(args, strings.NewReader(""), &stdout, &stderr)
		if code != 0 || stderr.Len() != 0 || stdout.String() != usage {
			t.Fatalf("run(%v) returned %d, stdout %q, stderr %q", args, code, &stdout, &stderr)
		}
	}
}

func TestDirectAndLegacyInvocation(t *testing.T) {
	flags := []string{"-", "--language", "kotlin", "--whole-file"}
	for _, args := range [][]string{flags, append([]string{"format"}, flags...)} {
		var stdout, stderr strings.Builder
		code := run(args, strings.NewReader(""), &stdout, &stderr)
		if code != 0 || stdout.Len() != 0 || stderr.Len() != 0 {
			t.Fatalf("run(%v) returned %d, stdout %q, stderr %q", args, code, &stdout, &stderr)
		}
	}
}

func TestDirectInvocationStillRequiresSelection(t *testing.T) {
	var stdout, stderr strings.Builder
	code := run([]string{"Main.java"}, strings.NewReader(""), &stdout, &stderr)
	if code != 2 || stdout.Len() != 0 || !strings.Contains(stderr.String(), "choose one of --lines, --range, and --whole-file") {
		t.Fatalf("got exit %d, stdout %q, stderr %q", code, &stdout, &stderr)
	}
}

func TestTimeoutAndContainerFlags(t *testing.T) {
	args := []string{"Main.java", "--lines", "1:2"}
	opt, _, err := parseFormat(append(args, "--timeout", "1.5s", "--container", "--config", "connection.json"))
	if err != nil || opt.timeout != 1500*time.Millisecond || !opt.container {
		t.Fatalf("unexpected options: %+v, %v", opt, err)
	}
	for _, flags := range [][]string{{"--timeout", "999us"}, {"--timeout", "2m1s"}, {"--container"}} {
		if _, _, err := parseFormat(append(args, flags...)); err == nil {
			t.Fatalf("expected invalid flags to fail: %v", flags)
		}
	}
}
