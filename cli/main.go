package main

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

const maxSourceBytes = 128 * 1024

const usage = `Format Java or Kotlin with the running IntelliJ IDEA plugin and its global code style.

Usage:
  ij-format [options] FILE...
  ij-format [options] - --language java|kotlin
  ij-format status [--config PATH] [--container]

Selection (choose one):
  --lines FIRST:LAST         Format a 1-based line range, including both lines.
  --range LINE:COL-LINE:COL  Format an exact range with an exclusive end.
                             Line and Unicode column numbers are 1-based.
                             Requires a plugin version that supports exact ranges.
  --whole-file               Format the entire input. Required for multiple files.

Output (choose at most one):
  --diff                     Show a unified diff.
  --check                    List files that need formatting and exit 1 if any differ.
  --write                    Replace changed files in place (files only).
  By default, print the complete formatted source for a single input to stdout.
  Multiple files require --diff, --check, or --write and infer their own languages.

Input and connection:
  FILE                       Infer Java from .java and Kotlin from .kt.
  -                          Read UTF-8 source from stdin. Use as the only input.
  --language java|kotlin     Required for stdin. Overrides inference for one file.
  --config PATH              Use IDEA's connection file or saved Copy MCP JSON.
                             Auto-detected when exactly one standard config matches.
  --timeout DURATION         Override the server format timeout per input (e.g. 3s).
  --container                Allow host.docker.internal with an explicit --config.
  -h, --help                 Show this help.
  Start IDEA and enable the server under Settings > Tools > IJ-format.
  Input must be UTF-8 and at most 128 KiB per file or stdin stream.

Examples:
  ij-format status
  ij-format Main.java --lines 12:38
  ij-format Main.java --lines 12:38 --diff
  ij-format Main.java --range 12:5-14:18 --write
  ij-format Main.java Example.kt --whole-file --check
  ij-format - --language kotlin --lines 3:9 < Example.kt

Exit codes:
  0  Success. With --check, all inputs are already formatted.
  1  At least one input needs formatting in --check mode.
  2  Invalid arguments, input, connection, or formatting error.
`

type exactRange struct {
	StartLine   int `json:"startLine"`
	StartColumn int `json:"startColumn"`
	EndLine     int `json:"endLine"`
	EndColumn   int `json:"endColumn"`
}

type options struct {
	files            []string
	language, config string
	selection, mode  string
	first, last      int
	exact            *exactRange
	timeout          time.Duration
	container        bool
}

var linePattern = regexp.MustCompile(`^([1-9][0-9]*):([1-9][0-9]*)$`)
var rangePattern = regexp.MustCompile(`^([1-9][0-9]*):([1-9][0-9]*)-([1-9][0-9]*):([1-9][0-9]*)$`)

func coordinates(value string, pattern *regexp.Regexp, expected string) ([]int, error) {
	match := pattern.FindStringSubmatch(value)
	if match == nil {
		return nil, errors.New(expected)
	}
	numbers := make([]int, 0, len(match)-1)
	for _, part := range match[1:] {
		number, err := strconv.Atoi(part)
		if err != nil {
			return nil, errors.New("line or column is too large")
		}
		numbers = append(numbers, number)
	}
	return numbers, nil
}

func (o *options) selectRange(flag, value string) error {
	if o.selection != "" && o.selection != flag {
		return errors.New("choose one of --lines, --range, and --whole-file")
	}
	o.selection = flag
	switch flag {
	case "--lines":
		n, err := coordinates(value, linePattern, "expected FIRST:LAST, with positive line numbers")
		if err != nil {
			return err
		}
		o.first, o.last = n[0], n[1]
		if o.first > o.last {
			return errors.New("FIRST must be at most LAST")
		}
	case "--range":
		n, err := coordinates(value, rangePattern, "expected LINE:COLUMN-LINE:COLUMN, with positive coordinates")
		if err != nil {
			return err
		}
		if n[0] > n[2] || n[0] == n[2] && n[1] >= n[3] {
			return errors.New("range start must precede its exclusive end")
		}
		o.exact = &exactRange{n[0], n[1], n[2], n[3]}
	}
	return nil
}

func parseFormat(args []string) (options, bool, error) {
	var opt options
	for i := 0; i < len(args); i++ {
		arg := args[i]
		if arg == "--" {
			opt.files = append(opt.files, args[i+1:]...)
			break
		}
		if arg == "-" || !strings.HasPrefix(arg, "-") {
			opt.files = append(opt.files, arg)
			continue
		}
		flag, value, hasValue := strings.Cut(arg, "=")
		switch flag {
		case "--language", "--config", "--lines", "--range", "--timeout":
			if !hasValue {
				if i+1 == len(args) || strings.HasPrefix(args[i+1], "-") {
					return opt, false, fmt.Errorf("%s requires a value", flag)
				}
				i++
				value = args[i]
			}
			switch flag {
			case "--language":
				if value != "java" && value != "kotlin" {
					return opt, false, errors.New("--language must be java or kotlin")
				}
				opt.language = value
			case "--timeout":
				timeout, err := time.ParseDuration(value)
				if err != nil || timeout < time.Millisecond || timeout > 2*time.Minute || timeout%time.Millisecond != 0 {
					return opt, false, errors.New("--timeout must be a whole number of milliseconds between 1ms and 2m")
				}
				opt.timeout = timeout
			case "--config":
				if value == "" {
					return opt, false, errors.New("--config requires a path")
				}
				opt.config = value
			default:
				if err := opt.selectRange(flag, value); err != nil {
					return opt, false, err
				}
			}
		case "--help", "-h", "--whole-file", "--check", "--diff", "--write", "--container":
			if hasValue {
				return opt, false, fmt.Errorf("%s takes no value", flag)
			}
			switch flag {
			case "--help", "-h":
				return opt, true, nil
			case "--container":
				opt.container = true
			case "--whole-file":
				if err := opt.selectRange(flag, ""); err != nil {
					return opt, false, err
				}
			default:
				if opt.mode != "" && opt.mode != flag {
					return opt, false, errors.New("choose one of --check, --diff, and --write")
				}
				opt.mode = flag
			}
		default:
			return opt, false, fmt.Errorf("unknown option %s", flag)
		}
	}
	if opt.container && opt.config == "" {
		return opt, false, errors.New("--container requires an explicit --config")
	}
	if len(opt.files) == 0 {
		return opt, false, errors.New("provide at least one FILE")
	}
	if opt.selection == "" {
		return opt, false, errors.New("choose one of --lines, --range, and --whole-file")
	}
	for _, file := range opt.files {
		if file == "-" && (len(opt.files) != 1 || opt.language == "" || opt.mode == "--write") {
			return opt, false, errors.New("stdin must be the only input, requires --language, and cannot use --write")
		}
	}
	if len(opt.files) > 1 && (opt.selection != "--whole-file" || opt.language != "" || opt.mode == "") {
		return opt, false, errors.New("multiple files require --whole-file, language inference, and --check, --diff or --write")
	}
	return opt, false, nil
}

func inputLanguage(filename, explicit string) (string, error) {
	if explicit != "" {
		return explicit, nil
	}
	base := filepath.Base(filename)
	extension := filepath.Ext(base)
	if extension != base {
		switch strings.ToLower(extension) {
		case ".java":
			return "java", nil
		case ".kt":
			return "kotlin", nil
		}
	}
	return "", errors.New("cannot infer language. Use a .java or .kt file, or specify --language")
}

func readSource(reader io.Reader) (string, error) {
	source, err := io.ReadAll(io.LimitReader(reader, maxSourceBytes+1))
	if err != nil {
		return "", err
	}
	if len(source) > maxSourceBytes {
		return "", errors.New("source exceeds the 128 KiB limit")
	}
	if !utf8.Valid(source) {
		return "", errors.New("source must be UTF-8")
	}
	return string(source), nil
}

func runFormat(opt options, stdin io.Reader, stdout, stderr io.Writer) int {
	var client *mcpClient
	var connectionErr error
	defer func() {
		if client != nil {
			client.close()
		}
	}()

	getClient := func() (*mcpClient, error) {
		if client == nil && connectionErr == nil {
			client, connectionErr = connect(opt.config, opt.container, opt.exact != nil, opt.timeout)
		}
		return client, connectionErr
	}

	code := 0
	for _, filename := range opt.files {
		changed, err := formatInput(filename, opt, getClient, stdin, stdout)
		if err != nil {
			fmt.Fprintf(stderr, "ij-format: %s: %v\n", filename, err)
			code = 2
		} else if changed && opt.mode == "--check" && code == 0 {
			code = 1
		}
	}
	return code
}

func formatInput(filename string, opt options, getClient func() (*mcpClient, error), stdin io.Reader, stdout io.Writer) (bool, error) {
	language, err := inputLanguage(filename, opt.language)
	if err != nil {
		return false, err
	}
	var original string
	var stat os.FileInfo
	if filename == "-" {
		original, err = readSource(stdin)
	} else {
		original, stat, err = readFile(filename, opt.mode == "--write")
	}
	if err != nil {
		return false, err
	}

	lineCount := strings.Count(original, "\n")
	if original != "" && !strings.HasSuffix(original, "\n") {
		lineCount++
	}
	args := formatArguments{Source: original, Language: language, TimeoutMs: opt.timeout.Milliseconds()}
	switch opt.selection {
	case "--lines":
		if opt.last > lineCount {
			return false, fmt.Errorf("line range exceeds source (%d lines)", lineCount)
		}
		args.StartLine, args.EndLine = opt.first, opt.last
	case "--whole-file":
		args.StartLine, args.EndLine = 1, lineCount
	case "--range":
		if original == "" {
			return false, errors.New("source is empty")
		}
		args.Range = opt.exact
	}

	result := original
	if original != "" {
		client, err := getClient()
		if err != nil {
			return false, err
		}
		result, err = client.format(args)
		if err != nil {
			return false, err
		}
	}
	changed := original != result
	switch opt.mode {
	case "--write":
		if changed {
			err = writeChanges(filename, original, result, stat)
		}
	case "--diff":
		if changed {
			err = printDiff(stdout, filename, original, result)
		}
	case "--check":
		if changed {
			_, err = fmt.Fprintln(stdout, filename)
		}
	default:
		_, err = io.WriteString(stdout, result)
	}
	return changed, err
}

func run(args []string, stdin io.Reader, stdout, stderr io.Writer) int {
	if len(args) == 0 {
		fmt.Fprint(stdout, usage)
		return 0
	}
	if args[0] == "status" {
		return runStatus(args[1:], stdout, stderr)
	}
	// Accept the original subcommand spelling for existing scripts.
	if args[0] == "format" {
		args = args[1:]
	}

	opt, help, err := parseFormat(args)
	if err != nil {
		fmt.Fprintf(stderr, "ij-format: %v\nRun 'ij-format --help' for usage and examples.\n", err)
		return 2
	}
	if help {
		fmt.Fprint(stdout, usage)
		return 0
	}
	return runFormat(opt, stdin, stdout, stderr)
}

func runStatus(args []string, stdout, stderr io.Writer) int {
	const help = "Usage: ij-format status [--config PATH] [--container]\n\nCheck the IDEA MCP connection, authentication, and available formatting ranges.\n--config accepts IDEA's connection file or saved Copy MCP JSON.\n"
	flags := flag.NewFlagSet("status", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	config := flags.String("config", "", "path to the MCP connection file")
	container := flags.Bool("container", false, "allow the container host address")
	err := flags.Parse(args)
	if errors.Is(err, flag.ErrHelp) {
		fmt.Fprint(stdout, help)
		return 0
	}
	if err == nil && flags.NArg() != 0 {
		err = errors.New("status takes no file arguments")
	}
	if err != nil {
		fmt.Fprintf(stderr, "ij-format status: %v\n", err)
		fmt.Fprint(stderr, help)
		return 2
	}
	if err := checkStatus(*config, *container, stdout); err != nil {
		fmt.Fprintf(stderr, "ij-format status: %v\n", err)
		return 2
	}
	return 0
}

func checkStatus(config string, container bool, stdout io.Writer) error {
	client, err := connect(config, container, false, 0)
	if err != nil {
		return err
	}
	defer client.close()
	capabilities, err := client.formatCapability()
	if err != nil {
		return err
	}
	selections := "--lines, --whole-file"
	if capabilities.exact {
		selections += ", --range"
	}
	if capabilities.timeout {
		selections += "\nRequest timeout: --timeout"
	}
	_, err = fmt.Fprintf(stdout, "IDEA MCP reachable at %s\nProtocol: %s\nFormatting: %s\n",
		client.url, client.headers.Get("MCP-Protocol-Version"), selections)
	return err
}

func main() {
	os.Exit(run(os.Args[1:], os.Stdin, os.Stdout, os.Stderr))
}
