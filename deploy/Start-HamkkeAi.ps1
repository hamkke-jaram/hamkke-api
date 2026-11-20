[CmdletBinding()]
param(
    [Parameter(Mandatory)][uri]$ApiOrigin,
    [string]$AiRoot = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'hamkke-ai'),
    [string]$StateDirectory = (Join-Path $env:LOCALAPPDATA 'HamkkeJaram/ai')
)

$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSEdition -eq 'Desktop') {
    # Use Windows PowerShell's DPAPI cmdlets even when a PowerShell 7 process starts the supervisor.
    Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Security/Microsoft.PowerShell.Security.psd1')
}
if (!$ApiOrigin.IsAbsoluteUri -or $ApiOrigin.Scheme -ne 'https' -or $ApiOrigin.UserInfo -or
    $ApiOrigin.AbsolutePath -ne '/' -or $ApiOrigin.Query -or $ApiOrigin.Fragment) {
    throw 'Supply the public HTTPS API origin without credentials, path, query, or fragment.'
}
$entry = (Resolve-Path -LiteralPath (Join-Path $AiRoot 'server/src/index.ts')).Path
$node = (Get-Command node.exe -CommandType Application).Source
$guard = New-Object System.Threading.Mutex($false, 'Local\HamkkeJaramAiSupervisor18090')
$ownsGuard = $false
try {
    try { $ownsGuard = $guard.WaitOne(0) }
    catch [System.Threading.AbandonedMutexException] { $ownsGuard = $true }
    if (-not $ownsGuard) { return }

    [IO.Directory]::CreateDirectory($StateDirectory) | Out-Null
    $state = (Resolve-Path -LiteralPath $StateDirectory).Path
    $secretPath = Join-Path $state 'ai-secret.dpapi'
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $bytes = New-Object byte[] 48
        if (-not (Test-Path -LiteralPath $secretPath)) {
            $random.GetBytes($bytes)
            $generated = ConvertTo-SecureString ([Convert]::ToBase64String($bytes)) -AsPlainText -Force
            try {
                $encrypted = ConvertFrom-SecureString -SecureString $generated
                $temporary = Join-Path $state ('ai-secret-' + [guid]::NewGuid().ToString('N') + '.tmp')
                [IO.File]::WriteAllText($temporary, $encrypted)
                [IO.File]::Move($temporary, $secretPath)
            } finally { $generated.Dispose() }
        }
        $random.GetBytes($bytes)
        $env:METRICS_TOKEN = [Convert]::ToBase64String($bytes)
        [Array]::Clear($bytes, 0, $bytes.Length)
    } finally { $random.Dispose() }

    $secured = ConvertTo-SecureString ([IO.File]::ReadAllText($secretPath).Trim())
    try { $env:AI_SHARED_SECRET = [System.Net.NetworkCredential]::new('', $secured).Password }
    finally { $secured.Dispose() }

    $env:NODE_ENV = 'production'
    $env:HOST = '127.0.0.1'
    $env:PORT = '18090'
    $env:OLLAMA_URL = 'http://127.0.0.1:11434'
    $env:DAYCARE_API_ORIGIN = $ApiOrigin.GetLeftPart([UriPartial]::Authority)
    $env:VL_MODEL = 'qwen3.5:4b'
    $env:VL_WARMUP = 'false'
    $env:VL_MAX_CONCURRENCY = '1'
    foreach ($unset in 'VL_FALLBACK_URL','VL_FALLBACK_MODEL','OLLAMA_AUTH_TOKEN','OLLAMA_AUTH_HEADER','OLLAMA_ALLOW_INSECURE') {
        [Environment]::SetEnvironmentVariable($unset, $null, 'Process')
    }

    $log = Join-Path $state 'ai-local.log'
    Set-Location -LiteralPath $AiRoot
    while ($true) {
        $ErrorActionPreference = 'Continue'
        & $node $entry 2>&1 | ForEach-Object {
            if ((Test-Path -LiteralPath $log) -and (Get-Item -LiteralPath $log).Length -ge 5MB) {
                [IO.File]::Copy($log, "$log.1", $true)
                [IO.File]::WriteAllText($log, '')
            }
            [IO.File]::AppendAllText($log, $_.ToString() + [Environment]::NewLine)
        }
        Start-Sleep -Seconds 10
    }
} finally {
    if ($ownsGuard) { $guard.ReleaseMutex() }
    $guard.Dispose()
}
