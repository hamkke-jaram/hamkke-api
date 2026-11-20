[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidatePattern('^[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?$')]
    [string]$Server,
    [string]$KeyDirectory,
    [ValidateRange(1, 65535)]
    [int]$SshPort = 22,
    [switch]$RegisterAtLogon,
    [ValidateSet('AI','Api')]
    [string]$Service = 'AI'
)

$ErrorActionPreference = 'Stop'
# Handle SSH disconnects in the retry loop even when the caller enables native error actions.
$PSNativeCommandUseErrorActionPreference = $false
$ssh = (Get-Command ssh.exe -CommandType Application).Source
$keygen = (Get-Command ssh-keygen.exe -CommandType Application).Source
$tunnelUser = 'hamkke-ai-tunnel'
$forward = '127.0.0.1:18090:127.0.0.1:18090'
$name = 'HamkkeJaram-AI-Tunnel'
$defaultKeyPath = '.ssh/hamkke-jaram'
if ($Service -eq 'Api') {
    $tunnelUser = 'hamkke-api-tunnel'
    $forward = '127.0.0.1:18980:127.0.0.1:18980'
    $name = 'HamkkeJaram-API-Tunnel'
    $defaultKeyPath = '.ssh/hamkke-jaram-api'
}
if (!$KeyDirectory) { $KeyDirectory = Join-Path $env:USERPROFILE $defaultKeyPath }
# Use relative file names so paths containing spaces work with Windows PowerShell 5.1 too.
Push-Location -LiteralPath $KeyDirectory
try {
    if (!(Test-Path -LiteralPath 'id_ed25519' -PathType Leaf)) {
        throw 'The dedicated id_ed25519 tunnel key is missing.'
    }
    if (!(Test-Path -LiteralPath 'known_hosts' -PathType Leaf)) {
        throw 'Create known_hosts using the public server key from the EC2 admin session.'
    }
    & $keygen -F hamkke-ec2 -f ./known_hosts | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'No hamkke-ec2 host key is pinned in known_hosts.' }

    if ($RegisterAtLogon) {
        $account = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        $keyPath = (Get-Location).ProviderPath
        $arguments = '-NoProfile -NonInteractive -ExecutionPolicy RemoteSigned -WindowStyle Hidden -File "{0}" -Server "{1}" -KeyDirectory "{2}" -SshPort {3} -Service {4}' -f $PSCommandPath, $Server, $keyPath, $SshPort, $Service
        $action = New-ScheduledTaskAction -Execute (Get-Command powershell.exe).Source -Argument $arguments
        $trigger = New-ScheduledTaskTrigger -AtLogOn -User $account
        $principal = New-ScheduledTaskPrincipal -UserId $account -LogonType Interactive -RunLevel Limited
        $settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
        Register-ScheduledTask -TaskName $name -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Force | Out-Null
        Start-ScheduledTask -TaskName $name
        return
    }

    $sshArgs = @(
        '-N', '-T', '-F', 'none', '-p', "$SshPort", '-i', './id_ed25519',
        '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes',
        '-o', 'StrictHostKeyChecking=yes', '-o', 'HostKeyAlgorithms=ssh-ed25519',
        '-o', 'UserKnownHostsFile=./known_hosts', '-o', 'GlobalKnownHostsFile=none',
        '-o', 'HostKeyAlias=hamkke-ec2', '-o', 'UpdateHostKeys=no',
        '-o', 'ConnectTimeout=10', '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=3',
        '-o', 'ExitOnForwardFailure=yes',
        '-R', $forward, '-l', $tunnelUser, $Server
    )
    while ($true) {
        & $ssh @sshArgs
        Write-Warning "$Service tunnel to $Server disconnected (ssh $LASTEXITCODE). Retrying in 10 seconds."
        Start-Sleep -Seconds 10
    }
} finally {
    Pop-Location
}
