<#
.SYNOPSIS
    mail-client Windows 卸载脚本：停止并移除 Windows 服务。
.DESCRIPTION
    默认保留安装目录（app/config/data/logs/backups）与数据库。
    传 -PurgeData 才删除安装目录。数据库永不自动删除。
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File uninstall.ps1
    powershell -ExecutionPolicy Bypass -File uninstall.ps1 -PurgeData
#>
[CmdletBinding()]
param(
    [string]$SettingsFile = '',
    [switch]$PurgeData
)

. (Join-Path $PSScriptRoot '_common.ps1')

if (-not $SettingsFile) { $SettingsFile = Join-Path (Join-Path $PSScriptRoot 'config') 'settings.json' }
if (-not (Test-Admin)) { throw '卸载需要管理员权限。' }
$s = $null
if (Test-Path $SettingsFile) { $s = Read-Settings -Path $SettingsFile }
else { Write-Log '未找到 settings.json，将尝试交互式服务名 mail-client。' 'WARN' }

$svc  = if ($s) { $s.serviceName } else { 'mail-client' }
$nssm = if ($s) { $s.nssmPath } else { $null }
if (-not $nssm -or -not (Test-Path $nssm)) {
    $c = Get-Command nssm -ErrorAction SilentlyContinue
    if ($c) { $nssm = $c.Source } else { throw '找不到 nssm.exe，无法移除服务。' }
}

if (Get-Service -Name $svc -ErrorAction SilentlyContinue) {
    Write-Log "停止并移除服务 $svc ..." 'STEP'
    Stop-Service2 -Nssm $nssm -ServiceName $svc
    & $nssm remove $svc confirm | Out-Host
    Write-Log '服务已移除。' 'OK'
} else {
    Write-Log "服务 $svc 不存在，跳过。" 'WARN'
}

if ($PurgeData -and $s -and (Test-Path $s.installDir)) {
    Write-Log "按 -PurgeData 删除安装目录: $($s.installDir)" 'WARN'
    Remove-Item $s.installDir -Recurse -Force
} else {
    if ($s) { Write-Log "保留安装目录: $($s.installDir)（含数据与备份）" 'INFO' }
}
Write-Log '注意：数据库 mail_client 未被删除，如需清理请手动 DROP DATABASE。' 'STEP'
