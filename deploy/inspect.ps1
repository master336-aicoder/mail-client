<#
.SYNOPSIS
    mail-client 系统巡检脚本。
.DESCRIPTION
    检查服务运行状态、端口可达、健康接口、数据库连通、Redis 连通、磁盘空间、
    日志大小、jar 版本等，输出一份巡检报告。
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File deploy\inspect.ps1
    powershell -ExecutionPolicy Bypass -File deploy\inspect.ps1 -OutFile C:\temp\inspect-report.txt
#>
[CmdletBinding()]
param(
    [string]$SettingsFile = '',
    [string]$OutFile = ''
)

. (Join-Path $PSScriptRoot '_common.ps1')

if (-not $SettingsFile) { $SettingsFile = Join-Path $PSScriptRoot 'config\settings.json' }

$s = $null
if (Test-Path $SettingsFile) { $s = Read-Settings -Path $SettingsFile }

$port       = if ($s -and $s.serverPort) { $s.serverPort } else { 8080 }
$installDir = if ($s -and $s.installDir) { $s.installDir } else { 'C:\mail-client' }
$svcName    = if ($s -and $s.serviceName) { $s.serviceName } else { 'mail-client' }
$dbHost     = if ($s) { $s.db.host } else { 'localhost' }
$dbPort     = if ($s) { $s.db.port } else { 3306 }
$dbName     = if ($s) { $s.db.name } else { 'mail_client' }
$redisHost  = if ($s) { $s.redis.host } else { 'localhost' }
$redisPort  = if ($s) { $s.redis.port } else { 6379 }
$baseUrl    = "http://localhost:$port"

$report = [System.Collections.ArrayList]::new()
function Add-Line([string]$text) { [void]$report.Add($text) }

Add-Line "============================================"
Add-Line "  mail-client 系统巡检报告"
Add-Line "  时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Add-Line "============================================"
Add-Line ""

# 1. 服务状态
Add-Line "[1] Windows 服务状态"
$svc = Get-Service -Name $svcName -ErrorAction SilentlyContinue
if ($svc) {
    Add-Line "    服务名: $svcName"
    Add-Line "    状态:   $($svc.Status)"
    Add-Line "    启动类型: $($svc.StartType)"
} else {
    Add-Line "    服务 $svcName 不存在（可能以 java -jar 方式运行）"
}
Add-Line ""

# 2. 端口监听
Add-Line "[2] 端口 $port 监听"
$listener = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
if ($listener) {
    Add-Line "    已监听 (PID: $($listener.OwningProcess | Select-Object -First 1))"
} else {
    Add-Line "    [异常] 端口 $port 未被监听"
}
Add-Line ""

# 3. 健康检查
Add-Line "[3] 健康检查 ($baseUrl/api/health)"
try {
    $r = Invoke-RestMethod -Uri "$baseUrl/api/health" -Method Get -TimeoutSec 10
    Add-Line "    status: $($r.status)"
    Add-Line "    db:     $($r.db)"
    Add-Line "    storage: $($r.storage)"
    if ($r.status -ne 'UP') { Add-Line "    [警告] status 非 UP" }
    if ($r.db -ne 'UP') { Add-Line "    [异常] 数据库不可达" }
} catch {
    Add-Line "    [异常] 健康检查失败: $($_.Exception.Message)"
}
Add-Line ""

# 4. 数据库连通
Add-Line "[4] 数据库连通 ($dbHost`:$dbPort/$dbName)"
$tcp = New-Object System.Net.Sockets.TcpClient
try {
    $tcp.Connect($dbHost, $dbPort)
    Add-Line "    TCP 连通"
    $tcp.Close()
} catch {
    Add-Line "    [异常] TCP 不通: $($_.Exception.Message)"
}
Add-Line ""

# 5. Redis 连通
Add-Line "[5] Redis 连通 ($redisHost`:$redisPort)"
$tcp2 = New-Object System.Net.Sockets.TcpClient
try {
    $tcp2.Connect($redisHost, $redisPort)
    Add-Line "    TCP 连通"
    $tcp2.Close()
} catch {
    Add-Line "    [异常] TCP 不通: $($_.Exception.Message)"
}
Add-Line ""

# 6. 磁盘空间
Add-Line "[6] 磁盘空间"
$drive = (Split-Path $installDir -Qualifier) + '\'
$vol = Get-WmiObject Win32_LogicalDisk -Filter "DeviceID='$drive'" -ErrorAction SilentlyContinue
if ($vol) {
    $freeGB = [math]::Round($vol.FreeSpace / 1GB, 2)
    $totalGB = [math]::Round($vol.Size / 1GB, 2)
    $usedPct = [math]::Round(($vol.Size - $vol.FreeSpace) / $vol.Size * 100, 1)
    Add-Line "    $drive  可用 ${freeGB}GB / 共 ${totalGB}GB (已用 ${usedPct}%)"
    if ($freeGB -lt 1) { Add-Line "    [异常] 磁盘空间不足 1GB" }
    elseif ($freeGB -lt 5) { Add-Line "    [警告] 磁盘空间不足 5GB" }
} else {
    Add-Line "    无法获取 $drive 磁盘信息"
}
Add-Line ""

# 7. 日志文件大小
Add-Line "[7] 日志文件"
$logDir = Join-Path $installDir 'logs'
if (Test-Path $logDir) {
    $logs = Get-ChildItem $logDir -File -ErrorAction SilentlyContinue
    foreach ($f in $logs) {
        $sizeMB = [math]::Round($f.Length / 1MB, 2)
        $warn = if ($sizeMB -gt 100) { ' [警告] >100MB' } else { '' }
        Add-Line "    $($f.Name): ${sizeMB}MB$warn"
    }
    if (-not $logs) { Add-Line "    日志目录为空" }
} else {
    Add-Line "    日志目录不存在: $logDir"
}
Add-Line ""

# 8. jar 版本
Add-Line "[8] 已安装版本"
$verFile = Join-Path $installDir 'VERSION.installed.json'
if (Test-Path $verFile) {
    $ver = Get-Content $verFile -Raw -Encoding UTF8 | ConvertFrom-Json
    Add-Line "    版本: $($ver.version)"
    Add-Line "    构建时间: $($ver.buildTime)"
    Add-Line "    Git: $($ver.gitCommit)"
    Add-Line "    jar SHA256: $($ver.jarSha256)"
} else {
    $jar = Get-ChildItem (Join-Path $installDir 'app') -Filter '*.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jar) { Add-Line "    jar: $($jar.Name) ($([math]::Round($jar.Length/1MB,1))MB)" }
    else { Add-Line "    未找到安装目录或 jar" }
}
Add-Line ""

# 9. 数据目录
Add-Line "[9] 数据目录"
$dataDir = Join-Path $installDir 'data'
if (Test-Path $dataDir) {
    foreach ($sub in @('ost','attachments','exports')) {
        $p = Join-Path $dataDir $sub
        if (Test-Path $p) {
            $items = (Get-ChildItem $p -Recurse -File -ErrorAction SilentlyContinue | Measure-Object).Count
            $sizeMB = [math]::Round((Get-ChildItem $p -Recurse -File -ErrorAction SilentlyContinue |
                        Measure-Object -Property Length -Sum).Sum / 1MB, 2)
            Add-Line "    $sub`: ${items} 个文件, ${sizeMB}MB"
        }
    }
} else {
    Add-Line "    数据目录不存在: $dataDir"
}
Add-Line ""

# 10. 备份目录
Add-Line "[10] 备份目录"
$backupDir = Join-Path $installDir 'backups'
if (Test-Path $backupDir) {
    $backups = Get-ChildItem $backupDir -Directory -ErrorAction SilentlyContinue | Sort-Object Name
    Add-Line "    共 $($backups.Count) 个备份"
    if ($backups) {
        $latest = $backups | Select-Object -Last 1
        Add-Line "    最近备份: $($latest.Name)"
    }
} else {
    Add-Line "    备份目录不存在"
}
Add-Line ""

Add-Line "============================================"
Add-Line "  巡检完毕"
Add-Line "============================================"

$output = $report -join "`n"
Write-Host $output

if ($OutFile) {
    $dir = Split-Path $OutFile -Parent
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    Set-Content $OutFile $output -Encoding UTF8
    Write-Log "报告已保存: $OutFile" 'OK'
}
