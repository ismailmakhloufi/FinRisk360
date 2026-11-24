# ============================================================
# FinRisk360 - LANCEMENT EN MODE DEVELOPPEMENT
#
# Base : finrisk (l'originale, non enrichie - cf. Start-Demo.ps1
# pour la base de demonstration finrisk_demo).
# ============================================================

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

Write-Host "[1/4] Arret des services existants..." -ForegroundColor Cyan
8080..8087 | ForEach-Object {
    Get-NetTCPConnection -LocalPort $_ -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique |
        ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }
}
Start-Sleep -Seconds 2

Write-Host "[2/4] Demarrage des 8 services (base = finrisk)..." -ForegroundColor Cyan
Remove-Item Env:\SPRING_DATASOURCE_URL -ErrorAction SilentlyContinue

# LLM optionnel via Ollama local (cf. Start-Demo.ps1 pour le details).
$ollamaBase = $env:OLLAMA_HOST
if (-not $ollamaBase) { $ollamaBase = 'localhost:11434' }
if ($ollamaBase -notmatch '^https?://') { $ollamaBase = "http://$ollamaBase" }
$env:AI_LLM_BASE_URL = $ollamaBase.TrimEnd('/') + '/v1'
$env:AI_LLM_MODEL    = 'llama3.1'
$env:AI_LLM_KEY      = 'ollama'

$logDir = Join-Path $env:TEMP 'opencode\dev'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

$services = 'api-gateway', 'auth-service', 'customer-service', 'account-service',
            'transaction-service', 'risk-service', 'alert-case-service', 'ai-assistant-service'

foreach ($svc in $services) {
    $jar = Get-ChildItem (Join-Path $root "backend\$svc\target\$svc-*.jar") |
        Select-Object -First 1
    if (-not $jar) { throw "JAR introuvable pour $svc - lance d'abord : mvn -q -DskipTests package" }
    Start-Process java -ArgumentList '-jar', "`"$($jar.FullName)`"" -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $logDir "$svc.out.log") `
        -RedirectStandardError  (Join-Path $logDir "$svc.err.log")
}

Write-Host "[3/4] Attente des health checks..." -ForegroundColor Cyan
$deadline = (Get-Date).AddSeconds(90)
$ports = 8080, 8081, 8082, 8083, 8084, 8085, 8086, 8087
do {
    Start-Sleep -Seconds 3
    $up = @($ports | Where-Object {
        try { Invoke-RestMethod "http://localhost:$_/actuator/health" -TimeoutSec 2 -ErrorAction Stop; $true }
        catch { $false }
    }).Count
    Write-Host "   services UP : $up / $($ports.Count)"
} while ($up -lt $ports.Count -and (Get-Date) -lt $deadline)

if ($up -lt $ports.Count) {
    Write-Warning "Certains services n'ont pas repondu - voir $logDir\*.err.log"
    exit 1
}

Write-Host "[4/4] Frontend Angular..." -ForegroundColor Cyan
$ng4200 = Get-NetTCPConnection -LocalPort 4200 -State Listen -ErrorAction SilentlyContinue
if ($ng4200) {
    Write-Host "   ng serve deja actif sur :4200" -ForegroundColor Green
} else {
    $npx = (Get-Command npx.cmd -ErrorAction SilentlyContinue).Source
    if (-not $npx) { throw "npx introuvable - Node.js installe ?" }
    Start-Process $npx -ArgumentList 'ng', 'serve' -WorkingDirectory (Join-Path $root 'frontend') `
        -WindowStyle Hidden -RedirectStandardOutput (Join-Path $logDir 'ng.out.log') `
        -RedirectStandardError (Join-Path $logDir 'ng.err.log')
    Write-Host "   ng serve lance (premier build ~30s)" -ForegroundColor Green
}

Write-Host ""
Write-Host "DEV PRET - http://localhost:4200  (base finrisk)" -ForegroundColor Yellow
