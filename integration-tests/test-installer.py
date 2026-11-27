#!/usr/bin/env python3
"""Exercise the release installer without downloading a public release."""

import argparse
import hashlib
import os
from pathlib import Path
import platform
import subprocess
from tempfile import TemporaryDirectory
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]


def test_installer(root, published_assets, version):
    os_name = {"Darwin": "darwin", "Linux": "linux"}[platform.system()]
    arch = {"arm64": "arm64", "aarch64": "arm64", "x86_64": "amd64"}[platform.machine()]
    cli_zip = f"ij-format-{version}-{os_name}-{arch}.zip"
    plugin_zip = f"ij-format-mcp-{version}.zip"
    assets = root / "assets"
    assets.mkdir()
    for name, entry in (
        (cli_zip, "ij-format"),
        (plugin_zip, f"ij-format-mcp/lib/idea-plugin-{version}.jar"),
    ):
        if published_assets:
            (assets / name).symlink_to(published_assets / name)
        else:
            with ZipFile(assets / name, "w") as archive:
                archive.writestr(entry, "#!/bin/sh\necho ij-format\n")
    checksums = assets / "SHA256SUMS"
    checksums.write_text(
        (published_assets / checksums.name).read_text() if published_assets else
        "".join(f"{hashlib.sha256((assets / name).read_bytes()).hexdigest()}  {name}\n"
                for name in (cli_zip, plugin_zip))
    )
    env = os.environ.copy()
    commands = root / "bin"
    commands.mkdir()
    curl = commands / "curl"
    curl.write_text(
        '#!/bin/sh\nset -eu\n'
        'test "$#" -eq 4\ntest "$1" = -fsSL\ntest "$3" = -o\n'
        'case "$2" in\n'
        '  "https://github.com/Yunie9/ij-format/releases/download/v$INSTALLER_TEST_VERSION/"*) ;;\n'
        '  *) exit 1 ;;\n'
        'esac\n'
        'cp "$INSTALLER_TEST_ASSETS/${2##*/}" "$4"\n'
    )
    curl.chmod(0o755)
    env.update(PATH=f"{commands}{os.pathsep}{env['PATH']}",
               INSTALLER_TEST_ASSETS=str(assets), INSTALLER_TEST_VERSION=version)
    installer = published_assets / "install.sh" if published_assets else ROOT / "scripts/install.sh"

    def run(component, destination):
        return subprocess.run(
            ["sh", str(installer), component, version, str(destination)],
            env=env, capture_output=True, text=True,
        )

    cli_dir = root / "CLI with spaces"
    cli_dir.mkdir()
    cli = cli_dir / "ij-format"
    cli.write_text("old binary")
    plugins = root / "plugins"
    old_plugin = plugins / "ij-format-mcp" / "lib" / "old.jar"
    old_plugin.parent.mkdir(parents=True)
    old_plugin.write_text("old plugin")

    valid_checksums = checksums.read_text()
    checksums.write_text(f"{'0' * 64}  {cli_zip}\n")
    result = run("cli", cli_dir)
    assert result.returncode == 1 and "SHA-256 mismatch" in result.stderr, result
    assert cli.read_text() == "old binary"
    assert not list(cli_dir.glob(".ij-format.*"))
    checksums.write_text(valid_checksums)

    for component, destination in (("cli", cli_dir), ("plugin", plugins)):
        result = run(component, destination)
        assert result.returncode == 0, result
        assert not list(destination.glob(".ij-format.*"))
    assert "ij-format" in subprocess.check_output([str(cli), "--help"], text=True)
    with ZipFile(assets / cli_zip) as archive:
        assert cli.read_bytes() == archive.read("ij-format")
    plugin_jar = f"idea-plugin-{version}.jar"
    with ZipFile(assets / plugin_zip) as archive:
        assert (old_plugin.parent / plugin_jar).read_bytes() == archive.read(f"ij-format-mcp/lib/{plugin_jar}")
    assert not old_plugin.exists()
    print("PASS installer verifies checksums, preserves existing files on failure, and upgrades CLI and plugin")

    # Redirect only the default destinations so the stdin path runs with zero arguments.
    script = installer.read_text()
    if not published_assets:
        script = script.replace("release_version=''", f"release_version='{version}'", 1)
    script = script.replace('destination:-"$HOME/.local/bin"', 'destination:-"$INSTALLER_TEST_CLI_DIR"')
    script = script.replace('destination:-"$idea_plugins"', 'destination:-"$INSTALLER_TEST_PLUGIN_DIR"')
    env.update(INSTALLER_TEST_CLI_DIR=str(root / "default CLI"),
               INSTALLER_TEST_PLUGIN_DIR=str(root / "default plugins"))
    result = subprocess.run(["bash"], input=script, env=env, capture_output=True, text=True)
    assert result.returncode == 0, result
    assert (root / "default CLI/ij-format").read_bytes() == cli.read_bytes()
    assert (root / "default plugins/ij-format-mcp/lib" / plugin_jar).is_file()
    print("PASS release script piped to Bash installs both components with no arguments")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets", type=Path, help="use locally packaged release assets")
    parser.add_argument("--version", default="0.4.0")
    args = parser.parse_args()
    with TemporaryDirectory(prefix="ij-format-installer-") as directory:
        test_installer(Path(directory), args.assets.resolve() if args.assets else None, args.version)
