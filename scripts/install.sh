#!/bin/sh
set -eu

release_version='' # Set by the release workflow.

usage() {
    echo 'Usage: sh install.sh [cli|plugin VERSION [DESTINATION_DIRECTORY]]' >&2
}

case "$(uname -s)" in
    Darwin)
        os=darwin
        idea_plugins="$HOME/Library/Application Support/JetBrains/IntelliJIdea2026.1/plugins"
        ;;
    Linux)
        os=linux
        idea_plugins="${XDG_DATA_HOME:-$HOME/.local/share}/JetBrains/IntelliJIdea2026.1"
        ;;
    *) echo 'Use the ZIP from GitHub Releases on this OS.' >&2; exit 2 ;;
esac

install_component() (
    component=$1
    destination=${2:-}

    if [ "$component" = cli ]; then
        case "$(uname -m)" in
            arm64|aarch64) arch=arm64 ;;
            x86_64|amd64) arch=amd64 ;;
            *) echo 'Unsupported CPU architecture.' >&2; exit 2 ;;
        esac
        asset="ij-format-$version-$os-$arch.zip"
        destination=${destination:-"$HOME/.local/bin"}
        target="$destination/ij-format"
    else
        asset="ij-format-mcp-$version.zip"
        destination=${destination:-"$idea_plugins"}
        target="$destination/ij-format-mcp"
        if [ -e "$target" ] && [ ! -d "$target" ]; then
            echo "Plugin path is not a directory: $target" >&2
            exit 1
        fi
    fi

    case "$destination" in
        /*) ;;
        *) echo 'Use an absolute destination directory.' >&2; exit 2 ;;
    esac
    if [ -L "$target" ] || { [ "$component" = cli ] && [ -e "$target" ] && [ ! -f "$target" ]; }; then
        echo "Cannot replace symbolic links or unexpected files: $target" >&2
        exit 1
    fi

    mkdir -p "$destination"
    work=$(mktemp -d "$destination/.ij-format.XXXXXX")
    cleanup() {
        if [ -d "$work/previous" ] && [ ! -e "$target" ]; then
            if ! mv "$work/previous" "$target"; then
                echo "Previous plugin retained at $work/previous" >&2
                return
            fi
        fi
        rm -rf "$work"
    }
    trap cleanup EXIT
    trap 'exit 1' HUP INT TERM

    release="https://github.com/Yunie9/ij-format/releases/download/v$version"
    curl -fsSL "$release/SHA256SUMS" -o "$work/SHA256SUMS"
    curl -fsSL "$release/$asset" -o "$work/$asset"
    expected=$(awk -v file="$asset" '$2 == file || $2 == "*" file { print $1 }' "$work/SHA256SUMS")
    if command -v sha256sum >/dev/null 2>&1; then
        actual=$(sha256sum "$work/$asset")
    else
        actual=$(shasum -a 256 "$work/$asset")
    fi
    if [ "$expected" != "${actual%% *}" ]; then
        echo "SHA-256 mismatch or missing checksum for $asset" >&2
        exit 1
    fi

    if [ "$component" = cli ]; then
        unzip -p "$work/$asset" ij-format > "$work/ij-format"
        chmod 755 "$work/ij-format"
        mv -f "$work/ij-format" "$target"
    else
        unzip -q "$work/$asset" -d "$work/extracted"
        if [ ! -f "$work/extracted/ij-format-mcp/lib/idea-plugin-$version.jar" ]; then
            echo "Unexpected plugin archive layout in $asset" >&2
            exit 1
        fi
        if [ -d "$target" ]; then
            mv "$target" "$work/previous"
        fi
        mv "$work/extracted/ij-format-mcp" "$target"
    fi
    printf 'Installed %s\n' "$target"
)

case "${1:-}" in
    --help|-h) usage; exit 0 ;;
    cli|plugin) component=$1 ;;
    "")
        if [ "$#" -ne 0 ] || [ -z "$release_version" ]; then usage; exit 2; fi
        version=$release_version
        install_component plugin
        install_component cli
        exit 0
        ;;
    *) usage; exit 2 ;;
esac
if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
    usage
    exit 2
fi
version=$2
case "$version" in
    *[!0123456789.]*) usage; exit 2 ;;
esac
if ! printf '%s\n' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    usage
    exit 2
fi
install_component "$component" "${3:-}"
