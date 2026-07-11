[CmdletBinding()]
param(
    [string]$Python = ''
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
if ([string]::IsNullOrWhiteSpace($Python)) {
    $Python = Join-Path $repoRoot '.venv-test\Scripts\python.exe'
}
if (-not (Test-Path -LiteralPath $Python -PathType Leaf)) {
    throw "The isolated unit-test environment was not found: $Python. Create .venv-test and install requirements-test.txt."
}
Push-Location $repoRoot
try {
    & $Python -B -m pytest -v
    if ($LASTEXITCODE -ne 0) {
        throw 'Private Chat unit tests failed.'
    }
} finally {
    Pop-Location
}
