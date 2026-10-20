<#
.SYNOPSIS
    mail-client 数据备份脚本（数据库 + 附件 + 配置）。
.DESCRIPTION
    备份内容：
      1) mysqldump 数据库（--single-transaction）
      2) 附件目录 data\attachments 打包
      3) 配置文件 config\application-prod.yml 快照
    备份保留策略：默认保留最近 7 份，可通过 -Retention 调整。
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File deploy\backup.ps1
    powershell -ExecutionPolicy Bypass -File deploy\backup.ps1 -Retention 14
    powershell -ExecutionPolicy Bypass -File deploy\backup.ps1 -SkipAttachments
#>
[CmdletBinding()]
param(
    [string]$SettingsFile = '',
    [string]$BackupRoot = '',
    [int]$Retention = 7,
    [switch]$SkipAttachments,
    [switch]$SkipDb
)

. (Join-Path $PSScriptRoot '_common.ps1')

if (-not $SettingsFile) { $SettingsFile = Join-Path $PSScriptRoot 'config\settings.json' }
if (-not (Test-Path $SettingsFile)) { throw "缺少 settings.json: $SettingsFile" }
$s = Read-Settings -Path $SettingsFile

$installDir = $s.installDir
if (-not $BackupRoot) { $BackupRoot = Join-Path $installDir 'backups' }
$ts = Get-Date -Format 'yyyyMMdd-HHmmss'
$dest = Join-Path $BackupRoot "backup-$ts"
New-Item -ItemType Directory -Path $dest -Force | Out-Null

Write-Log "开始备份 -> $dest" 'STEP'

# 1. 数据库
if (-not $SkipDb) {
    $mysqldump = $s.db.mysqlBin -replace 'mysql\.exe$', 'mysqldump.exe'
    if (Test-Path $mysqldump) {
        $sqlFile = Join-Path $dest "$($s.db.name).sql"
        Write-Log '备份数据库...' 'STEP'
        & $mysqldump -h $s.db.host -P $s.db.port -u $s.db.user "-p$($s.db.password)" `
            --single-transaction --routines --triggers $s.db.name > $sqlFile
        if ($LASTEXITCODE -ne 0) {
            Write-Log "mysqldump 退出码 $LASTEXITCODE，备份可能不完整。" 'WARN'
        } else {
            $sizeMB = [math]::Round((Get-Item $sqlFile).Length / 1MB, 2)
            Write-Log "数据库备份完成: ${sizeMB}MB" 'OK'
        }
    } else {
        Write-Log "未找到 mysqldump.exe ($mysqldump)，跳过数据库备份。" 'WARN'
    }
}

# 2. 附件
if (-not $SkipAttachments) {
    $attachDir = Join-Path $installDir 'data\attachments'
    if (Test-Path $attachDir) {
        $zipFile = Join-Path $dest 'attachments.zip'
        Write-Log '备份附件目录...' 'STEP'
        Compress-Archive -Path $attachDir -DestinationPath $zipFile -Force
        $sizeMB = [math]::Round((Get-Item $zipFile).Length / 1MB, 2)
        Write-Log "附件备份完成: ${sizeMB}MB" 'OK'
    } else {
        Write-Log "附件目录不存在: $attachDir，跳过。" 'INFO'
    }
}

# 3. 配置快照
$configFile = Join-Path $installDir 'config\application-prod.yml'
if (Test-Path $configFile) {
    Copy-Item $configFile (Join-Path $dest 'application-prod.yml') -Force
    Write-Log '配置快照完成' 'OK'
}

# 4. settings.json 快照
if (Test-Path $SettingsFile) {
    Copy-Item $SettingsFile (Join-Path $dest 'settings.json') -Force
}

# 5. 清理旧备份
$allBackups = Get-ChildItem $BackupRoot -Directory |
    Where-Object { $_.Name -match '^backup-' } |
    Sort-Object Name
if ($allBackups.Count -gt $Retention) {
    $toRemove = $allBackups | Select-Object -First ($allBackups.Count - $Retention)
    foreach ($old in $toRemove) {
        Write-Log "清理旧备份: $($old.Name)" 'INFO'
        Remove-Item $old.FullName -Recurse -Force
    }
}

Write-Log "备份完成: $dest" 'OK'
