$ErrorActionPreference = 'Stop'
$email = (Read-Host 'Adresse Gmail qui enverra et recevra le signal').Trim()
if ($email -notmatch '^[^@\s]+@gmail\.com$') {
    throw 'Saisissez une adresse Gmail.'
}

$entered = Read-Host 'Mot de passe application Google (16 caracteres)' -AsSecureString
$password = [Net.NetworkCredential]::new('', $entered).Password -replace '\s', ''
if ($password -notmatch '^[A-Za-z0-9]{16}$') {
    throw 'Utilisez le mot de passe application Google de 16 caracteres.'
}

$directory = Join-Path $env:LOCALAPPDATA 'FinRisk360'
New-Item -ItemType Directory -Path $directory -Force | Out-Null
@{
    SMS_WAKE_GMAIL_USER = $email
    SMS_WAKE_GMAIL_APP_PASSWORD = ConvertTo-SecureString $password -AsPlainText -Force
} | Export-Clixml -LiteralPath (Join-Path $directory 'sms-wake-gmail.xml')
Remove-Variable password, entered
Write-Output 'Configuration Gmail enregistree pour cet utilisateur Windows.'
