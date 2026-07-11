[CmdletBinding()]
param(
    [string]$Python = ''
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
if ([string]::IsNullOrWhiteSpace($Python)) {
    $Python = Join-Path $repoRoot '.venv-interop\Scripts\python.exe'
}
if (-not (Test-Path -LiteralPath $Python -PathType Leaf)) {
    throw "The isolated interoperability environment was not found: $Python. Create .venv-interop and install requirements-interop.txt."
}
Push-Location $repoRoot
try {
    & $Python -B -m pytest -v -rs server/private-chat/tests/interop_encrypted_matrix.py
    if ($LASTEXITCODE -ne 0) {
        throw 'Private Chat encrypted Matrix interoperability test failed.'
    }
} finally {
    Pop-Location
}
