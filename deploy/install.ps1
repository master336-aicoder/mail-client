<#
.SYNOPSIS
    mail-client Windows 安装脚本：将打包产物注册为 NSSM 服务并启动。
.DESCRIPTION
    步骤：管理员校验 -> 读取/生成 settings.json -> 预检(java/nssm/mysql) ->
    建目录/拷 jar -> 渲染 application-prod.yml -> 建库 -> 注册 NSSM 服务(注入密钥) -> 启动 -> 健康检查。
    升级请使用 upgrade.ps1，本脚本遇到同名服务会中止。
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File install.ps1              # 首次生成 settings.json 并退出，供核对
    powershell -ExecutionPolicy Bypass -File install.ps1 -Confirm     # 核对 settings.json 后正式安装
#>
[CmdletBinding()]
param(
    [string]$SettingsFile = '',
    [switch]$Confirm   # 已核对 settings.json，执行真正的安装
)

. (Join-Path $PSScriptRoot '_common.ps1')

if (-not $SettingsFile) { $SettingsFile = Join-Path (Join-Path $PSScriptRoot 'config') 'settings.json' }
$Root       = Split-Path $PSScriptRoot -Parent          # 发布包根（含 app/ config/ scripts/）
$TemplateYml= Join-Path $PSScriptRoot 'config\application-prod.template.yml'
if (-not (Test-Path $TemplateYml)) { $TemplateYml = Join-Path $Root 'config\application-prod.template.yml' }

# ---------- 首次运行：生成 settings.json ----------
if (-not (Test-Path $SettingsFile)) {
    $example = Join-Path $PSScriptRoot 'config\settings.example.json'
    if (-not (Test-Path $example)) { $example = Join-Path $Root 'config\settings.example.json' }
    $s = Get-Content $example -Raw -Encoding UTF8 | ConvertFrom-Json
    $s.jwtSecret = New-RandomHex 32
    $s.masterKey = New-RandomHex 32
    Write-Settings -Path $SettingsFile -Settings $s
    Write-Log "已生成设置文件: $SettingsFile（含随机 JWT_SECRET / MASTER_KEY）。" 'OK'
    Write-Log '请核对其中的数据库/Redis 账号、publicOrigin、端口、路径等，然后带 -Confirm 重新运行以正式安装。' 'STEP'
    Write-Log "示例: powershell -ExecutionPolicy Bypass -File install.ps1 -Confirm" 'STEP'
    return
}

$s = Read-Settings -Path $SettingsFile

if (-not $Confirm) {
    Write-Log "检测到 settings.json，但未加 -Confirm。确认配置无误后运行: install.ps1 -Confirm" 'WARN'
    Write-Log "当前关键配置: installDir=$($s.installDir) port=$($s.serverPort) publicOrigin=$($s.publicOrigin) db=$($s.db.host):$($s.db.port)/$($s.db.name)" 'INFO'
    return
}

# ---------- 管理员校验 ----------
if (-not (Test-Admin)) { throw '安装需要管理员权限（注册 Windows 服务）。请以管理员身份重新运行 PowerShell。' }

# ---------- 预检 ----------
Write-Log '预检运行环境与工具...' 'STEP'
$javaExe  = Resolve-Java -Preferred $s.javaHome
Write-Log "java: $javaExe"
if (-not (Test-Path $s.nssmPath)) {
    $nssmCmd = Get-Command nssm -ErrorAction SilentlyContinue
    if ($nssmCmd) { $s.nssmPath = $nssmCmd.Source }
    else { throw "找不到 nssm.exe：$($s.nssmPath)。请下载 NSSM 放到该路径，或将 nssm 加入 PATH 后重试。" }
}
Write-Log "nssm: $($s.nssmPath)"

$jar = Get-ChildItem (Join-Path $Root 'app') -Filter '*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'sources|original' } | Select-Object -First 1
if (-not $jar) { throw "发布包 app 目录未找到可执行 jar: $(Join-Path $Root 'app')" }
Write-Log "jar: $($jar.FullName)"

# ---------- 目录与产物 ----------
Write-Log "创建安装目录: $($s.installDir)" 'STEP'
$dirs = @('app','config','logs','backups','data\ost','data\attachments','data\exports')
foreach ($d in $dirs) { New-Item -ItemType Directory -Path (Join-Path $s.installDir $d) -Force | Out-Null }
Copy-Item $jar.FullName (Join-Path $s.installDir "app\$($jar.Name)") -Force
$jarTarget = Join-Path $s.installDir "app\$($jar.Name)"

# ---------- 渲染 application-prod.yml ----------
Write-Log '生成 config\application-prod.yml...' 'STEP'
$tpl = Get-Content $TemplateYml -Raw -Encoding UTF8
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
$prodYml = Join-Path $s.installDir 'config\application-prod.yml'
Set-Content $prodYml $tpl -Encoding UTF8

# ---------- 建库 ----------
Write-Log "确保数据库存在: $($s.db.name)..." 'STEP'
if (Test-Path $s.db.mysqlBin) {
    $createSql = "CREATE DATABASE IF NOT EXISTS ``$($s.db.name)`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
    & $s.db.mysqlBin -h $s.db.host -P $s.db.port -u $s.db.user "-p$($s.db.password)" -e $createSql
    if ($LASTEXITCODE -ne 0) { throw "创建数据库失败（mysql.exe 退出码 $LASTEXITCODE）。请核对 db.* 配置。" }
    Write-Log "数据库已就绪（表结构将由 Flyway 在首次启动时自动创建）。" 'OK'
} else {
    Write-Log "未在 $($s.db.mysqlBin) 找到 mysql.exe，跳过自动建库。请手动执行: $createSql" 'WARN'
}

# ---------- 注册服务 ----------
if (Get-Service -Name $s.serviceName -ErrorAction SilentlyContinue) {
    throw "服务 $($s.serviceName) 已存在。升级请用 upgrade.ps1；如需重装请先运行 uninstall.ps1。"
}
Write-Log "注册 Windows 服务: $($s.serviceName)..." 'STEP'
$appArgs = "-jar `"$jarTarget`" --spring.profiles.active=prod"
& $s.nssmPath install $s.serviceName $javaExe $appArgs | Out-Host
& $s.nssmPath set $s.serviceName AppDirectory "$($s.installDir)" | Out-Host
& $s.nssmPath set $s.serviceName AppStdout (Join-Path $s.installDir 'logs\stdout.log') | Out-Host
& $s.nssmPath set $s.serviceName AppStderr (Join-Path $s.installDir 'logs\stderr.log') | Out-Host
& $s.nssmPath set $s.serviceName AppRotateFiles 1 | Out-Host
& $s.nssmPath set $s.serviceName AppRotateBytes 10485760 | Out-Host
& $s.nssmPath set $s.serviceName Start SERVICE_AUTO_START | Out-Host
# 注入密钥（不落到 yml）
& $s.nssmPath set $s.serviceName AppEnvironmentExtra "JWT_SECRET=$($s.jwtSecret)" "MASTER_KEY=$($s.masterKey)" | Out-Host
if ($s.serviceUser -and $s.serviceUser -ne 'LocalSystem') {
    & $s.nssmPath set $s.serviceName ObjectName $s.serviceUser | Out-Host
}
& $s.nssmPath set $s.serviceName DisplayName 'Mail Client Backend' | Out-Host
& $s.nssmPath set $s.serviceName Description 'Web 邮箱客户端后端（Spring Boot，单进程托管前端 SPA）' | Out-Host

# ---------- 启动 + 健康检查 ----------
Write-Log '启动服务...' 'STEP'
Start-Service2 -Nssm $s.nssmPath -ServiceName $s.serviceName
$base = "http://localhost:$($s.serverPort)"
if (Invoke-HealthCheck -BaseUrl $base -TimeoutSec 120) {
    Write-Log "安装成功。访问入口（同源）: $($s.publicOrigin.TrimEnd('/'))/" 'OK'
    Write-Log "健康检查: $base/api/health   服务名: $($s.serviceName)" 'INFO'
    Write-Log "如需对外访问，请在 Windows 防火墙放行端口 $($s.serverPort)。" 'STEP'
    Write-Log "HTTPS 部署：请置于反向代理/网关后，并将 settings.authCookieSecure 改为 true 后重跑 upgrade 以更新配置。" 'STEP'
} else {
    Write-Log "服务已注册但健康检查未通过。请查看日志: $(Join-Path $s.installDir 'logs')" 'ERROR'
    Write-Log '排障: nssm status ' + $s.serviceName + ' ; Get-Content ' + (Join-Path $s.installDir 'logs\stderr.log') + ' -Tail 50' 'STEP'
    throw '安装未完成：健康检查失败。'
}
