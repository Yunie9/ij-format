package main

import (
	"errors"
	"os"
	"path/filepath"
)

func readFile(path string, writable bool) (string, os.FileInfo, error) {
	var before os.FileInfo
	if writable {
		info, err := os.Lstat(path)
		if err != nil {
			return "", nil, err
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return "", nil, errors.New("cannot write to a symbolic link")
		}
		if !info.Mode().IsRegular() {
			return "", nil, errors.New("cannot write to a non-regular file")
		}
		before = info
	}
	f, err := os.Open(path)
	if err != nil {
		return "", nil, err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return "", nil, err
	}
	if writable && !sameFileState(before, info) {
		return "", nil, errChangedFile
	}
	source, err := readSource(f)
	return source, info, err
}

var errChangedFile = errors.New("file changed while it was being formatted")

func sameFileState(a, b os.FileInfo) bool {
	return os.SameFile(a, b) && a.Mode() == b.Mode() && a.Size() == b.Size() && a.ModTime().Equal(b.ModTime())
}

func writeChanges(path, original, formatted string, initial os.FileInfo) error {
	temp, err := os.CreateTemp(filepath.Dir(path), ".ij-format-")
	if err != nil {
		return err
	}
	defer os.Remove(temp.Name())

	_, writeErr := temp.WriteString(formatted)
	chmodErr := temp.Chmod(initial.Mode() & (os.ModePerm | os.ModeSetuid | os.ModeSetgid | os.ModeSticky))
	closeErr := temp.Close()
	if err := errors.Join(writeErr, chmodErr, closeErr); err != nil {
		return err
	}

	source, current, err := readFile(path, true)
	if err != nil {
		return err
	}
	latest, err := os.Lstat(path)
	if err != nil {
		return err
	}
	if source != original || !sameFileState(initial, current) || !sameFileState(current, latest) {
		return errChangedFile
	}
	return os.Rename(temp.Name(), path)
}
