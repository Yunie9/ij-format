[CmdletBinding()]
param(
    [Parameter(Mandatory, Position = 0)]
    [ValidateSet('cli', 'plugin')]
    [string]$Component,

    [Parameter(Mandatory, Position = 1)]
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')]
    [string]$Version,

    [Parameter(Position = 2)]
    [string]$Destination
)

$ErrorActionPreference = 'Stop'
if ($Component -eq 'cli') {
    $arch = switch ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()) {
        'X64' { 'amd64' }
        'Arm64' { 'arm64' }
        default { throw 'Unsupported CPU architecture.' }
    }
    $asset = "ij-format-$Version-windows-$arch.zip"
    $entry = 'ij-format.exe'
    if (!$Destination) { $Destination = Join-Path $env:LOCALAPPDATA 'ij-format\bin' }
} else {
    $asset = "ij-format-mcp-$Version.zip"
    $entry = 'ij-format-mcp'
    if (!$Destination) { $Destination = Join-Path $env:APPDATA 'JetBrains\IntelliJIdea2026.1\plugins' }
}

if (![IO.Path]::IsPathFullyQualified($Destination)) { throw 'Use an absolute destination directory.' }
$null = New-Item -ItemType Directory -Force -Path $Destination
$target = Join-Path $Destination $entry
$current = Get-Item -LiteralPath $target -Force -ErrorAction SilentlyContinue
if ($current -and (($current.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        ($current.PSIsContainer -ne ($Component -eq 'plugin')))) {
    throw "Cannot replace a link or unexpected file type at $target"
}

$work = Join-Path $Destination ('.ij-format.' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $work
$backup = Join-Path $work 'previous'
try {
    $release = "https://github.com/Yunie9/ij-format/releases/download/v$Version"
    $sums = Join-Path $work 'SHA256SUMS'
    $archive = Join-Path $work $asset
    Invoke-WebRequest -Uri "$release/SHA256SUMS" -OutFile $sums
    Invoke-WebRequest -Uri "$release/$asset" -OutFile $archive
    $pattern = '^([a-fA-F0-9]{64})\s+\*?' + [regex]::Escape($asset) + '$'
    $expected = @(Select-String -LiteralPath $sums -Pattern $pattern)
    if ($expected.Count -ne 1 -or (Get-FileHash -LiteralPath $archive).Hash -ne $expected[0].Matches[0].Groups[1].Value) {
        throw "SHA-256 mismatch or missing checksum for $asset"
    }
    $extracted = Join-Path $work 'extracted'
    Expand-Archive -LiteralPath $archive -DestinationPath $extracted
    $payload = Join-Path $extracted $entry
    $requiredFile = $payload
    if ($Component -eq 'plugin') { $requiredFile = Join-Path $payload "lib\idea-plugin-$Version.jar" }
    if (!(Test-Path -LiteralPath $requiredFile -PathType Leaf)) { throw "Unexpected archive layout in $asset" }
    if ($current) { Move-Item -LiteralPath $target -Destination $backup }
    Move-Item -LiteralPath $payload -Destination $target
} finally {
    if ((Test-Path -LiteralPath $backup) -and !(Test-Path -LiteralPath $target)) {
        Move-Item -LiteralPath $backup -Destination $target
    }
    Remove-Item -LiteralPath $work -Force -Recurse
}
Write-Output "Installed $target"
