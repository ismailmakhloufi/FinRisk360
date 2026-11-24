# ============================================================
# FinRisk360 - LANCEMENT DE LA DEMONSTRATION (jour J)
#
# Base : finrisk_demo  (clone enrichi par postgres\03-seed-demo.sql)
# Aucun fichier de code n'est modifie : on passe seulement la variable
# d'environnement SPRING_DATASOURCE_URL, qui prime sur application.yml.
#
# Utilisation :   .\Start-Demo.ps1
# Retour dev  :   .\Start-Dev.ps1   (meme script, base finrisk)
# ============================================================

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

# --- 1. Arret des services actuels (8080-8087) ----------------
Write-Host "[1/4] Arret des services existants..." -ForegroundColor Cyan
8080..8087 | ForEach-Object {
    Get-NetTCPConnection -LocalPort $_ -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique |
        ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }
}
Start-Sleep -Seconds 2

# --- 2. Lancement des 8 jars POINTES sur finrisk_demo ----------
Write-Host "[2/4] Demarrage des 8 services (base = finrisk_demo)..." -ForegroundColor Cyan
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://localhost:5432/finrisk_demo'

# LLM optionnel via Ollama local (chat narratif IA).
# AI_LLM_KEY active le LlmClient ; si Ollama est eteint, le service
# bascule AUTOMATIQUEMENT sur le moteur deterministe (la demo ne casse jamais).
$ollamaBase = $env:OLLAMA_HOST
if (-not $ollamaBase) { $ollamaBase = 'localhost:11434' }
if ($ollamaBase -notmatch '^https?://') { $ollamaBase = "http://$ollamaBase" }
$ollamaBase = $ollamaBase.TrimEnd('/')
$env:AI_LLM_BASE_URL = $ollamaBase + '/v1'
$env:AI_LLM_MODEL    = 'llama3.1'
$env:AI_LLM_KEY      = 'ollama'

# Prechauffage : 1er chargement du modele = ~43s, ensuite ~2.5s.
# On le lance en arriere-plan PENDANT le demarrage des services.
if (Get-Command ollama -ErrorAction SilentlyContinue) {
    Start-Job -ArgumentList ($ollamaBase + '/api/generate') -ScriptBlock {
        param($warmupUrl)
        try {
            Invoke-RestMethod $warmupUrl -Method Post `
                -ContentType 'application/json' `
                -Body '{"model":"llama3.1","prompt":"ping","stream":false}' -TimeoutSec 120 | Out-Null
        } catch { }
    } | Out-Null
    Write-Host "   prechauffage Ollama (llama3.1) en arriere-plan" -ForegroundColor Green
}

$logDir = Join-Path $env:TEMP 'opencode\demo'
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

# --- 3. Attente des health checks ------------------------------
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

# --- 4. Frontend Angular ---------------------------------------
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
Write-Host "DEMO PRETE - http://localhost:4200" -ForegroundColor Yellow
Write-Host "   base : finrisk_demo (14 clients / 203 transactions)" -ForegroundColor Yellow
