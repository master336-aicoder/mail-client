<#
.SYNOPSIS
    mail-client Windows 升级脚本：备份 -> 换件 -> Flyway 自动迁移 -> 健康检查 -> 失败回滚。
.DESCRIPTION
    用新发布的 zip（或已解包的 ReleaseDir）覆盖当前安装：
    1) mysqldump 备份数据库，快照当前 jar/配置到 backups\<ts>\
    2) 停止服务，替换 jar，按 settings.json 重渲染 application-prod.yml
    3) 启动服务，Flyway 在启动时自动执行数据库迁移
    4) 健康检查；失败则自动回滚到旧 jar/配置并重启
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File upgrade.ps1 -ReleaseDir D:\tmp\mail-client-1.0.1
#>
[CmdletBinding()]
param(
    [string]$SettingsFile = '',   # 默认取 scripts\config\settings.json
    [Parameter(Mandatory)][string]$ReleaseDir,   # 新版本解包目录（含 app\*.jar 与 config\ 模板）
    [switch]$SkipBackup,
    [switch]$SkipHealthCheck
)

. (Join-Path $PSScriptRoot '_common.ps1')

if (-not $SettingsFile) { $SettingsFile = Join-Path (Join-Path $PSScriptRoot 'config') 'settings.json' }
if (-not (Test-Admin)) { throw '升级需要管理员权限。' }
if (-not (Test-Path $SettingsFile)) { throw "缺少 settings.json: $SettingsFile" }
$s = Read-Settings -Path $SettingsFile

$ReleaseDir = (Resolve-Path $ReleaseDir).Path
$newJar = Get-ChildItem (Join-Path $ReleaseDir 'app') -Filter '*.jar' |
          Where-Object { $_.Name -notmatch 'sources|original' } | Select-Object -First 1
if (-not $newJar) { throw "新版本目录未找到 jar: $(Join-Path $ReleaseDir 'app')" }

# 模板：优先取发布包内，其次脚本目录
$tplPath = Join-Path $ReleaseDir 'config\application-prod.template.yml'
if (-not (Test-Path $tplPath)) { $tplPath = Join-Path $PSScriptRoot 'config\application-prod.template.yml' }
if (-not (Test-Path $tplPath)) { throw "找不到 application-prod.template.yml 模板。" }

$ts        = Get-Date -Format 'yyyyMMdd-HHmmss'
$backupDir = Join-Path $s.installDir "backups\$ts"
$prodYml   = Join-Path $s.installDir 'config\application-prod.yml'
$appDir    = Join-Path $s.installDir 'app'
$oldJar    = Get-ChildItem $appDir -Filter '*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
$base      = "http://localhost:$($s.serverPort)"

Write-Log "升级版本 jar: $($newJar.Name)" 'STEP'
Write-Log "备份目录: $backupDir"

# ---------- 1. 备份 ----------
New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
if (-not $SkipBackup) {
    if ($oldJar) { Copy-Item $oldJar.FullName $backupDir -Force; Write-Log '已快照旧 jar' }
    if (Test-Path $prodYml) { Copy-Item $prodYml (Join-Path $backupDir 'application-prod.yml') -Force; Write-Log '已快照旧配置' }
    if ($s.version) { }
    # 记录当前版本清单
    $verJson = Join-Path $ReleaseDir 'VERSION.json'
    if (Test-Path $verJson) { Copy-Item $verJson (Join-Path $backupDir 'new-VERSION.json') -Force }
    if ($oldJar) {
        $prev = Join-Path $s.installDir 'VERSION.installed.json'
        if (Test-Path $prev) { Copy-Item $prev $backupDir -Force }
    }

    $dump = $s.db.mysqlBin -replace 'mysql\.exe$', 'mysqldump.exe'
    if (Test-Path $dump) {
        Write-Log 'mysqldump 备份数据库...' 'STEP'
        $sql = Join-Path $backupDir "$($s.db.name)-$ts.sql"
        & $dump -h $s.db.host -P $s.db.port -u $s.db.user "-p$($s.db.password)" --single-transaction --routines --triggers $s.db.name > $sql
        if ($LASTEXITCODE -ne 0) { Write-Log "mysqldump 退出码 $LASTEXITCODE，备份可能不完整。" 'WARN' }
        else { Write-Log "数据库备份: $sql" 'OK' }
    } else {
        Write-Log "未找到 mysqldump.exe（$dump），跳过 DB 备份。建议手动备份后重试。" 'WARN'
    }
} else {
    Write-Log '已按 -SkipBackup 跳过备份。' 'WARN'
}

# ---------- 2. 停服 + 换件 ----------
Write-Log '停止服务并替换产物...' 'STEP'
Stop-Service2 -Nssm $s.nssmPath -ServiceName $s.serviceName
Start-Sleep -Seconds 2

if ($oldJar) { Remove-Item $oldJar.FullName -Force }
Copy-Item $newJar.FullName (Join-Path $appDir $newJar.Name) -Force

# 重渲染配置
$tpl = Get-Content $tplPath -Raw -Encoding UTF8
$map = @{
    '__SERVER_PORT__'        = "$($s.serverPort)"
    '__DB_HOST__'            = $s.db.host
    '__DB_PORT__'            = "$($s.db.port)"
    '__DB_NAME__'            = $s.db.name
    '__DB_USER__'            = $s.db.user
    '__DB_PASSWORD__'        = $s.db.password
    '__REDIS_HOST__'         = $s.redis.host
    '__REDIS_PORT__'         = "$($s.redis.port)"
    '__REDIS_PASSWORD__'     = $s.redis.password
    '__ALLOW_PRIVATE_HOST__' = ([bool]$s.allowPrivateHost).ToString().ToLower()
    '__AUTH_COOKIE_SECURE__' = ([bool]$s.authCookieSecure).ToString().ToLower()
    '__PUBLIC_ORIGIN__'      = $s.publicOrigin
    '__STORAGE_MODE__'       = $s.storageMode
}
foreach ($k in $map.Keys) { $tpl = $tpl.Replace($k, $map[$k]) }
Set-Content $prodYml $tpl -Encoding UTF8

# 记录已安装版本清单
$verJson = Join-Path $ReleaseDir 'VERSION.json'
if (Test-Path $verJson) { Copy-Item $verJson (Join-Path $s.installDir 'VERSION.installed.json') -Force }

# ---------- 3. 启动（Flyway 自动迁移）+ 健康检查 ----------
Write-Log '启动服务（Flyway 将自动执行迁移）...' 'STEP'
Start-Service2 -Nssm $s.nssmPath -ServiceName $s.serviceName

if ($SkipHealthCheck) {
    Write-Log '按 -SkipHealthCheck 跳过健康检查。请自行访问 /api/health 确认。' 'WARN'
    return
}

function Test-Ok { Invoke-HealthCheck -BaseUrl $base -TimeoutSec 120 }

if (Test-Ok) {
    Write-Log '升级成功，健康检查通过。' 'OK'
    Write-Log "回滚快照保留在: $backupDir" 'INFO'
    return
}

# ---------- 4. 回滚 ----------
Write-Log '健康检查失败，开始自动回滚...' 'ERROR'
Stop-Service2 -Nssm $s.nssmPath -ServiceName $s.serviceName
if ($oldJar) {
    $rolled = Get-ChildItem $appDir -Filter '*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($rolled) { Remove-Item $rolled.FullName -Force }
    Copy-Item $oldJar.FullName (Join-Path $appDir $oldJar.Name) -Force
}
$oldYml = Join-Path $backupDir 'application-prod.yml'
if (Test-Path $oldYml) { Copy-Item $oldYml $prodYml -Force }
Write-Log '已回滚 jar/配置；数据库如需回滚，请用备份手动恢复：' 'WARN'
Write-Log "  mysql -h $($s.db.host) -P $($s.db.port) -u $($s.db.user) -p $($s.db.name) < `"$backupDir\$($s.db.name)-$ts.sql`"" 'STEP'
Write-Log '尝试用旧版本重新启动服务...' 'STEP'
Start-Service2 -Nssm $s.nssmPath -ServiceName $s.serviceName
if (Test-Ok) { Write-Log '回滚后旧版本健康检查通过。' 'OK' }
else { Write-Log '回滚后仍不健康，请人工排查日志: ' + (Join-Path $s.installDir 'logs') 'ERROR' }
throw '升级失败，已回滚到旧版本。'
