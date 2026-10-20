<#
.SYNOPSIS
    mail-client Windows 服务启停管理脚本。
.DESCRIPTION
    通过 NSSM 管理 mail-client 服务的启动、停止、重启、状态查看。
    无需指定 settings.json 时自动使用默认服务名 mail-client。
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File deploy\service.ps1 status
    powershell -ExecutionPolicy Bypass -File deploy\service.ps1 start
    powershell -ExecutionPolicy Bypass -File deploy\service.ps1 stop
    powershell -ExecutionPolicy Bypass -File deploy\service.ps1 restart
    powershell -ExecutionPolicy Bypass -File deploy\service.ps1 restart -Tail 50   # 重启后查看最近 50 行日志
#>
[CmdletBinding()]
param(
    [Parameter(Position=0, Mandatory)]
    [ValidateSet('start','stop','restart','status')]
    [string]$Action,

    [string]$ServiceName = 'mail-client',
    [string]$NssmPath = '',
    [string]$InstallDir = 'C:\mail-client',
    [int]$Tail = 0
)

. (Join-Path $PSScriptRoot '_common.ps1')

# 尝试从 settings.json 读取配置
$settingsFile = Join-Path $PSScriptRoot 'config\settings.json'
if (Test-Path $settingsFile) {
    $s = Read-Settings -Path $settingsFile
    if (-not $NssmPath -and $s.nssmPath) { $NssmPath = $s.nssmPath }
    if ($s.serviceName) { $ServiceName = $s.serviceName }
    if ($s.installDir) { $InstallDir = $s.installDir }
}

if (-not $NssmPath) {
    $cmd = Get-Command nssm -ErrorAction SilentlyContinue
    if ($cmd) { $NssmPath = $cmd.Source }
    else { throw '找不到 nssm.exe，请设置 -NssmPath 或将 nssm 加入 PATH。' }
}

function Get-ServiceStatus {
    try {
        $st = & $NssmPath status $ServiceName 2>&1
        return "$st"
    } catch {
        return 'UNKNOWN'
    }
}

switch ($Action) {
    'start' {
        $st = Get-ServiceStatus
        if ($st -match 'SERVICE_RUNNING') {
            Write-Log "服务 $ServiceName 已在运行中。" 'INFO'
        } else {
            Write-Log "启动服务 $ServiceName ..." 'STEP'
            Start-Service2 -Nssm $NssmPath -ServiceName $ServiceName
            Start-Sleep -Seconds 2
            $st = Get-ServiceStatus
            Write-Log "服务状态: $st" 'OK'
        }
    }
    'stop' {
        $st = Get-ServiceStatus
        if ($st -match 'SERVICE_RUNNING|SERVICE_START_PENDING') {
            Write-Log "停止服务 $ServiceName ..." 'STEP'
            Stop-Service2 -Nssm $NssmPath -ServiceName $ServiceName
            Start-Sleep -Seconds 2
            Write-Log '服务已停止。' 'OK'
        } else {
            Write-Log "服务 $ServiceName 未在运行（$st）。" 'INFO'
        }
    }
    'restart' {
        Write-Log "重启服务 $ServiceName ..." 'STEP'
        Stop-Service2 -Nssm $NssmPath -ServiceName $ServiceName
        Start-Sleep -Seconds 2
        Start-Service2 -Nssm $NssmPath -ServiceName $ServiceName
        Start-Sleep -Seconds 3
        $st = Get-ServiceStatus
        Write-Log "服务状态: $st" 'OK'
        if (Invoke-HealthCheck -TimeoutSec 60) {
            Write-Log '健康检查通过。' 'OK'
        } else {
            Write-Log '健康检查未通过，请查看日志。' 'ERROR'
        }
    }
    'status' {
        $st = Get-ServiceStatus
        Write-Log "服务 $ServiceName 状态: $st" 'INFO'
        # 附带健康检查
        $port = 8080
        if ($s -and $s.serverPort) { $port = $s.serverPort }
        try {
            $r = Invoke-RestMethod -Uri "http://localhost:$port/api/health" -Method Get -TimeoutSec 5
            Write-Log "健康检查: status=$($r.status) db=$($r.db) storage=$($r.storage)" 'OK'
        } catch {
            Write-Log "健康检查失败: $($_.Exception.Message)" 'ERROR'
        }
    }
}

if ($Tail -gt 0) {
    $logFile = Join-Path $InstallDir 'logs\stderr.log'
    if (Test-Path $logFile) {
        Write-Log "最近 $Tail 行日志 ($logFile):" 'STEP'
        Get-Content $logFile -Tail $Tail
    } else {
        Write-Log "日志文件不存在: $logFile" 'WARN'
    }
}
