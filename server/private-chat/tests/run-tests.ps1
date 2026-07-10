[CmdletBinding()]
param(
    [string]$Python = 'D:\Python310\python.exe'
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
if (-not (Test-Path -LiteralPath $Python -PathType Leaf)) {
    throw "Python was not found: $Python"
}
Push-Location $repoRoot
try {
    & $Python -B -m unittest discover -s server/private-chat/tests -p 'test_*.py' -v
    if ($LASTEXITCODE -ne 0) {
        throw 'Private Chat unit tests failed.'
    }
} finally {
    Pop-Location
}
