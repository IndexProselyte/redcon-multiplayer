param(
    [string]$DestinationRoot = ""
)

$ErrorActionPreference = "Stop"
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
if ([string]::IsNullOrWhiteSpace($DestinationRoot)) {
    $DestinationRoot = Join-Path $repo "build\release-tools"
}
$DestinationRoot = [IO.Path]::GetFullPath($DestinationRoot)
$archive = Join-Path $DestinationRoot "xdelta3-3.2.0-windows-x86_64.zip"
$directory = Join-Path $DestinationRoot "xdelta3-3.2.0-windows-x86_64"
$executable = Join-Path $directory "xdelta3.exe"
$archiveSha256 = "8aca331c3d49ec4465ee8f3f7e3afb92e06d36e32fdec422c3252aaa813a7d2a"
$executableSha256 = "6e812b38484d0c764291779fadee83ffbe2eceaccb4e3a21f8a043a02654a01e"
$url = "https://github.com/jmacd/xdelta/releases/download/v3.2.0/xdelta3-3.2.0-windows-x86_64.zip"

function Assert-Sha256([string]$Path, [string]$Expected, [string]$Label) {
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $Expected) { throw "$Label SHA-256 mismatch: expected $Expected, got $actual" }
}

if (Test-Path -LiteralPath $executable -PathType Leaf) {
    Assert-Sha256 $executable $executableSha256 "xdelta executable"
    Write-Host "PASS - pinned xdelta executable already present: $executable"
    exit 0
}

New-Item -ItemType Directory -Path $DestinationRoot -Force | Out-Null
if (-not (Test-Path -LiteralPath $archive -PathType Leaf)) {
    $download = "$archive.download"
    if (Test-Path -LiteralPath $download) {
        throw "incomplete download already exists; inspect and remove it before retrying: $download"
    }
    Invoke-WebRequest -Uri $url -OutFile $download
    Assert-Sha256 $download $archiveSha256 "xdelta release archive"
    Move-Item -LiteralPath $download -Destination $archive
}
Assert-Sha256 $archive $archiveSha256 "xdelta release archive"

if (Test-Path -LiteralPath $directory) {
    throw "xdelta destination exists but the executable is missing: $directory"
}
Expand-Archive -LiteralPath $archive -DestinationPath $directory
Assert-Sha256 $executable $executableSha256 "xdelta executable"
Write-Host "PASS - downloaded and verified xdelta 3.2.0: $executable"
