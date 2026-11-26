param([string]$PluginArchive)

$ErrorActionPreference = 'Stop'
$installer = Join-Path $PSScriptRoot '../scripts/install.ps1'
$work = Join-Path ([IO.Path]::GetTempPath()) ('ijf-installer-' + [guid]::NewGuid().ToString('N'))
$assets = Join-Path $work 'assets'
$fixture = Join-Path $work 'fixture'
$cliDir = Join-Path $work 'CLI with spaces'
$pluginDir = Join-Path $work 'plugins'
$arch = switch ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()) {
    'X64' { 'amd64' }
    'Arm64' { 'arm64' }
    default { throw 'Unsupported test architecture.' }
}
$version = '0.4.0'
if ($PluginArchive) {
    $archiveName = Split-Path $PluginArchive -Leaf
    if ($archiveName -notmatch '^ij-format-mcp-([0-9]+\.[0-9]+\.[0-9]+)\.zip$') {
        throw "Unexpected plugin archive name $archiveName"
    }
    $version = $Matches[1]
}
$cliZip = "ij-format-$version-windows-$arch.zip"
$pluginZip = "ij-format-mcp-$version.zip"

function Invoke-WebRequest([string]$Uri, [string]$OutFile) {
    $base = "https://github.com/Yunie9/ij-format/releases/download/v$version/"
    if (!$Uri.StartsWith($base)) { throw "Unexpected download URL $Uri" }
    Copy-Item -LiteralPath (Join-Path $assets ($Uri.Substring($base.Length))) -Destination $OutFile
}

try {
    $null = New-Item -ItemType Directory -Path $assets, $fixture, $cliDir, $pluginDir
    $binary = Join-Path $fixture 'ij-format.exe'
    $plugin = Join-Path $fixture 'ij-format-mcp'
    $pluginJar = "lib/idea-plugin-$version.jar"
    if ($PluginArchive) {
        go -C "$PSScriptRoot/../cli" build -trimpath -ldflags="-s -w -X main.cliVersion=$version" -o $binary .
        if ($LASTEXITCODE -ne 0) { throw 'Windows CLI build failed.' }
        Copy-Item -LiteralPath $PluginArchive -Destination (Join-Path $assets $pluginZip)
        Expand-Archive -LiteralPath $PluginArchive -DestinationPath $fixture
    } else {
        $null = New-Item -ItemType Directory -Path (Join-Path $plugin 'lib') -Force
        Set-Content -LiteralPath $binary -Value 'new CLI' -NoNewline
        Set-Content -LiteralPath (Join-Path $plugin $pluginJar) -Value 'new plugin' -NoNewline
        Compress-Archive -LiteralPath $plugin -DestinationPath (Join-Path $assets $pluginZip)
    }
    Compress-Archive -LiteralPath $binary -DestinationPath (Join-Path $assets $cliZip)
    $sums = Join-Path $assets 'SHA256SUMS'
    $validSums = foreach ($asset in $cliZip, $pluginZip) {
        "$((Get-FileHash -LiteralPath (Join-Path $assets $asset)).Hash)  $asset"
    }
    $target = Join-Path $cliDir 'ij-format.exe'
    Set-Content -LiteralPath $target -Value 'old CLI' -NoNewline
    Set-Content -LiteralPath $sums -Value ('0' * 64 + "  $cliZip")
    try {
        & $installer cli $version $cliDir
        throw 'Invalid checksum was accepted.'
    } catch {
        if ($_.Exception.Message -notmatch 'SHA-256 mismatch') { throw }
    }
    if ((Get-Content -LiteralPath $target -Raw) -ne 'old CLI') { throw 'Failed install changed the CLI.' }

    Set-Content -LiteralPath $sums -Value $validSums
    & $installer cli $version $cliDir
    & $installer plugin $version $pluginDir
    if ((Get-FileHash -LiteralPath $target).Hash -ne (Get-FileHash -LiteralPath $binary).Hash) {
        throw 'Unexpected CLI payload.'
    }
    $installedPlugin = Join-Path $pluginDir 'ij-format-mcp'
    if ((Get-FileHash -LiteralPath (Join-Path $installedPlugin $pluginJar)).Hash -ne
        (Get-FileHash -LiteralPath (Join-Path $plugin $pluginJar)).Hash) {
        throw 'Unexpected plugin payload.'
    }
    if ($PluginArchive) {
        $help = & $target --help
        if ($LASTEXITCODE -ne 0 -or ($help -join "`n") -notmatch 'ij-format') { throw 'Installed CLI did not print help.' }
        foreach ($jar in Get-ChildItem -LiteralPath (Join-Path $plugin 'lib') -Filter '*.jar') {
            if ((Get-FileHash -LiteralPath (Join-Path $installedPlugin "lib/$($jar.Name)")).Hash -ne
                (Get-FileHash -LiteralPath $jar.FullName).Hash) {
                throw "Unexpected installed JAR $($jar.Name)."
            }
        }
    }
    Write-Output 'PASS PowerShell installer checks both packages and preserves the CLI on checksum failure'
} finally {
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Force -Recurse }
}
