package main

import (
	"bufio"
	"fmt"
	"io"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/sergi/go-diff/diffmatchpatch"
)

const diffContextLines = 3

type lineChange struct {
	oldStart, oldEnd int
	newStart, newEnd int
}

func changedLines(original, formatted string) []lineChange {
	matcher := diffmatchpatch.New()
	oldTokens, newTokens, _ := matcher.DiffLinesToRunes(original, formatted)
	var changes []lineChange
	oldLine, newLine := 0, 0
	for _, edit := range matcher.DiffMainRunes(oldTokens, newTokens, false) {
		// Each encoded rune represents one whole line.
		count := utf8.RuneCountInString(edit.Text)
		if edit.Type == diffmatchpatch.DiffEqual {
			oldLine += count
			newLine += count
			continue
		}
		if len(changes) == 0 || changes[len(changes)-1].oldEnd != oldLine || changes[len(changes)-1].newEnd != newLine {
			changes = append(changes, lineChange{oldStart: oldLine, newStart: newLine})
		}
		switch edit.Type {
		case diffmatchpatch.DiffDelete:
			oldLine += count
		case diffmatchpatch.DiffInsert:
			newLine += count
		}
		last := &changes[len(changes)-1]
		last.oldEnd, last.newEnd = oldLine, newLine
	}
	return changes
}

func splitDiffLines(text string) []string {
	lines := strings.SplitAfter(text, "\n")
	if lines[len(lines)-1] == "" {
		lines = lines[:len(lines)-1]
	}
	return lines
}

func hunkRange(start, end int) string {
	switch end - start {
	case 0:
		return strconv.Itoa(start) + ",0"
	case 1:
		return strconv.Itoa(start + 1)
	default:
		return fmt.Sprintf("%d,%d", start+1, end-start)
	}
}

func printDiff(out io.Writer, label, original, formatted string) error {
	if label == "-" {
		label = "stdin"
	}
	a, b := splitDiffLines(original), splitDiffLines(formatted)
	changes := changedLines(original, formatted)
	w := bufio.NewWriter(out)
	writeLines := func(prefix byte, lines []string) {
		for _, line := range lines {
			w.WriteByte(prefix)
			w.WriteString(line)
			if !strings.HasSuffix(line, "\n") {
				w.WriteString("\n\\ No newline at end of file\n")
			}
		}
	}

	fmt.Fprintf(w, "--- %s\n+++ %s\n", label, label)
	for len(changes) > 0 {
		hunkSize := 1
		for hunkSize < len(changes) && changes[hunkSize].oldStart-changes[hunkSize-1].oldEnd <= 2*diffContextLines {
			hunkSize++
		}
		first, last := changes[0], changes[hunkSize-1]
		before := min(diffContextLines, first.oldStart)
		after := min(diffContextLines, len(a)-last.oldEnd)
		oldStart, oldEnd := first.oldStart-before, last.oldEnd+after
		newStart, newEnd := first.newStart-before, last.newEnd+after
		fmt.Fprintf(w, "@@ -%s +%s @@\n", hunkRange(oldStart, oldEnd), hunkRange(newStart, newEnd))

		line := oldStart
		for _, change := range changes[:hunkSize] {
			writeLines(' ', a[line:change.oldStart])
			writeLines('-', a[change.oldStart:change.oldEnd])
			writeLines('+', b[change.newStart:change.newEnd])
			line = change.oldEnd
		}
		writeLines(' ', a[line:oldEnd])
		changes = changes[hunkSize:]
	}
	return w.Flush()
}
