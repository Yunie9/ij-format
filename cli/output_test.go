package main

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestDiffPreservesLineEndings(t *testing.T) {
	var out strings.Builder
	err := printDiff(&out, "-", "class A {\r\n  int x=1;\r\n}", "class A {\r\n  int x = 1;\r\n}")
	if err != nil {
		t.Fatal(err)
	}
	want := "--- stdin\n+++ stdin\n@@ -1,3 +1,3 @@\n class A {\r\n-  int x=1;\r\n+  int x = 1;\r\n }\n\\ No newline at end of file\n"
	if out.String() != want {
		t.Fatalf("got %q, want %q", out.String(), want)
	}
}

func TestDiffKeepsRepeatedLinesAsContext(t *testing.T) {
	repeated := strings.Repeat("  run();\n", 500)
	var out strings.Builder
	if err := printDiff(&out, "Sample.java", repeated+"  run(1);\n"+repeated, repeated+"  run(2);\n"+repeated); err != nil {
		t.Fatal(err)
	}
	context := strings.Repeat("   run();\n", 3)
	want := "--- Sample.java\n+++ Sample.java\n@@ -498,7 +498,7 @@\n" + context + "-  run(1);\n+  run(2);\n" + context
	if out.String() != want {
		t.Fatalf("got %q, want %q", out.String(), want)
	}
}

func TestDiffHunks(t *testing.T) {
	for _, tt := range []struct {
		name, old, new, want string
	}{
		{"merged", "a\nb\nc\nd\ne\nf\ng\nh\n", "A\nb\nc\nd\ne\nf\ng\nH\n",
			"@@ -1,8 +1,8 @@\n-a\n+A\n b\n c\n d\n e\n f\n g\n-h\n+H\n"},
		{"separate", "a\nb\nc\nd\ne\nf\ng\nh\ni\n", "A\nb\nc\nd\ne\nf\ng\nh\nI\n",
			"@@ -1,4 +1,4 @@\n-a\n+A\n b\n c\n d\n@@ -6,4 +6,4 @@\n f\n g\n h\n-i\n+I\n"},
		{"insert", "", "雪\n", "@@ -0,0 +1 @@\n+雪\n"},
		{"delete", "雪", "", "@@ -1 +0,0 @@\n-雪\n\\ No newline at end of file\n"},
	} {
		t.Run(tt.name, func(t *testing.T) {
			var out strings.Builder
			if err := printDiff(&out, "Sample.kt", tt.old, tt.new); err != nil {
				t.Fatal(err)
			}
			want := "--- Sample.kt\n+++ Sample.kt\n" + tt.want
			if out.String() != want {
				t.Fatalf("got %q, want %q", out.String(), want)
			}
		})
	}
}

func TestWriteKeepsEditMadeAfterRead(t *testing.T) {
	path := filepath.Join(t.TempDir(), "A.java")
	original := "int x=1;"
	edited := "int x=2;"
	if err := os.WriteFile(path, []byte(original), 0640); err != nil {
		t.Fatal(err)
	}
	_, initial, err := readFile(path, true)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(edited), 0640); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(path, initial.ModTime(), initial.ModTime()); err != nil {
		t.Fatal(err)
	}
	if err := writeChanges(path, original, "int x = 1;", initial); !errors.Is(err, errChangedFile) {
		t.Fatalf("got %v, want %v", err, errChangedFile)
	}
	content, err := os.ReadFile(path)
	if err != nil || string(content) != edited {
		t.Fatalf("got %q, %v, want %q", content, err, edited)
	}
}
