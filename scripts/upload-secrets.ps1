<#
.SYNOPSIS
  Carica nei GitHub Secrets del repository i dati di firma di CutBet (e, a richiesta, rinomina il repository).

.DESCRIPTION
  Legge keystore.properties e il keystore indicato (entrambi LOCALI, esclusi da git) e imposta:
    SIGNING_KEYSTORE_BASE64, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD
  I valori passano a "gh secret set" dallo standard input: non compaiono mai a schermo, nei log
  né nella riga di comando. Il file non contiene segreti e può stare nel repository.

  Requisiti: GitHub CLI installata e autenticata (winget install GitHub.cli ; gh auth login).

.PARAMETER RenameTo
  Facoltativo: nuovo nome del repository (es. CutBet). Rinomina su GitHub e aggiorna il remote origin.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\upload-secrets.ps1 -RenameTo CutBet
#>
param([string]$RenameTo = "")

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    throw "GitHub CLI non trovata. Installa con: winget install GitHub.cli  e poi: gh auth login"
}
gh auth status | Out-Null
if ($LASTEXITCODE -ne 0) { throw "GitHub CLI non autenticata: esegui gh auth login" }

if ($RenameTo) {
    $current = gh repo view --json name --jq .name
    if ($current -ne $RenameTo) {
        gh repo rename $RenameTo --yes
        if ($LASTEXITCODE -ne 0) { throw "Rinomina del repository fallita" }
    }
    $url = gh repo view --json url --jq .url
    git remote set-url origin "$url.git"
    Write-Host "Repository: $url"
}

$propsFile = Join-Path $root "keystore.properties"
if (-not (Test-Path $propsFile)) { throw "keystore.properties non trovato in $root" }
$props = @{}
foreach ($line in Get-Content $propsFile) {
    if ($line -match '^\s*([^#=\s]+)\s*=\s*(.*)$') { $props[$Matches[1]] = $Matches[2].Trim() }
}
foreach ($k in "storeFile", "storePassword", "keyAlias", "keyPassword") {
    if (-not $props[$k]) { throw "Manca '$k' in keystore.properties" }
}
$keystore = Join-Path $root $props["storeFile"]
if (-not (Test-Path $keystore)) { throw "Keystore non trovato: $keystore" }

function Set-Secret([string]$name, [string]$value) {
    $value | gh secret set $name
    if ($LASTEXITCODE -ne 0) { throw "Impostazione di $name fallita" }
    Write-Host "Impostato $name"
}

Set-Secret "SIGNING_KEYSTORE_BASE64" ([Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore)))
Set-Secret "SIGNING_STORE_PASSWORD" $props["storePassword"]
Set-Secret "SIGNING_KEY_ALIAS" $props["keyAlias"]
Set-Secret "SIGNING_KEY_PASSWORD" $props["keyPassword"]

Write-Host ""
Write-Host "Fatto. Avvia la build firmata da GitHub (Actions -> Android release -> Run workflow) oppure con:"
Write-Host "  gh workflow run android-release.yml"
