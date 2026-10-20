<#
.SYNOPSIS
    mail-client 日志查看脚本。
.DESCRIPTION
    快速查看服务日志，支持实时跟踪、按关键字过滤、查看最近 N 行。
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File deploy\logs.ps1                    # 最近 50 行
    powershell -ExecutionPolicy Bypass -File deploy\logs.ps1 -Tail 200          # 最近 200 行
    powershell -ExecutionPolicy Bypass -File deploy\logs.ps1 -Follow             # 实时跟踪
    powershell -ExecutionPolicy Bypass -File deploy\logs.ps1 -Filter 'ERROR'    # 过滤关键字
    powershell -ExecutionPolicy Bypass -File deploy\logs.ps1 -Stdout            # 查看 stdout 日志
#>
[CmdletBinding()]
param(
    [string]$InstallDir = 'C:\mail-client',
    [int]$Tail = 50,
    [switch]$Follow,
    [string]$Filter = '',
    [switch]$Stdout
)

. (Join-Path $PSScriptRoot '_common.ps1')

# 尝试从 settings.json 读取安装目录
$settingsFile = Join-Path $PSScriptRoot 'config\settings.json'
if (Test-Path $settingsFile) {
    $s = Read-Settings -Path $settingsFile
    if ($s.installDir) { $InstallDir = $s.installDir }
}

$logName = if ($Stdout) { 'stdout.log' } else { 'stderr.log' }
$logFile = Join-Path $InstallDir "logs\$logName"

if (-not (Test-Path $logFile)) {
    Write-Log "日志文件不存在: $logFile" 'ERROR'
    Write-Log '请确认安装目录配置正确，或尝试 -Stdout 查看 stdout 日志。' 'INFO'
    return
}

$fileSize = [math]::Round((Get-Item $logFile).Length / 1MB, 2)
Write-Log "日志文件: $logFile (${fileSize}MB)" 'INFO'

if ($Follow) {
    Write-Log '实时跟踪中... (Ctrl+C 退出)' 'STEP'
    Get-Content $logFile -Tail $Tail -Wait
} elseif ($Filter) {
    Write-Log "过滤关键字: $Filter" 'STEP'
    Get-Content $logFile | Select-String -Pattern $Filter -SimpleMatch
} else {
    Get-Content $logFile -Tail $Tail
}
