# _common.ps1 — mail-client Windows 部署共享函数
# 被 package.ps1 / install.ps1 / upgrade.ps1 / uninstall.ps1 点源使用。
# 不含业务逻辑，仅提供日志、健康检查、随机密钥、设置读写等工具。

$ErrorActionPreference = 'Stop'

function Write-Log {
    param(
        [Parameter(Mandatory)][string]$Message,
        [ValidateSet('INFO', 'WARN', 'ERROR', 'OK', 'STEP')][string]$Level = 'INFO'
    )
    $ts = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    $color = switch ($Level) {
        'INFO'  { 'Gray' }
        'STEP'  { 'Cyan' }
        'OK'    { 'Green' }
        'WARN'  { 'Yellow' }
        'ERROR' { 'Red' }
    }
    Write-Host "[$ts] [$Level] $Message" -ForegroundColor $color
}

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    $p = New-Object Security.Principal.WindowsPrincipal($id)
    return $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function New-RandomHex {
    param([int]$Bytes = 32)
    $buf = New-Object byte[] $Bytes
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buf)
    return -join ($buf | ForEach-Object { $_.ToString('x2') })
}

function Read-Settings {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path $Path)) {
        throw "设置文件不存在: $Path"
    }
    return Get-Content $Path -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Write-Settings {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)]$Settings)
    $dir = Split-Path $Path -Parent
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $Settings | ConvertTo-Json -Depth 10 | Set-Content $Path -Encoding UTF8
}

# 轮询 /api/health 直至 status=UP 或超时。
function Invoke-HealthCheck {
    param(
        [string]$BaseUrl = 'http://localhost:8080',
        [int]$TimeoutSec = 90,
        [int]$IntervalSec = 3
    )
    $url = "$BaseUrl/api/health"
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $r = Invoke-RestMethod -Uri $url -Method Get -TimeoutSec 5
            # /api/health 走统一 ApiResponse 信封，字段在 .data 下；兼容直接返回裸 map 的情况
            $h = if ($r.data) { $r.data } else { $r }
            if ($h.status -eq 'UP' -and $h.db -eq 'UP') {
                Write-Log "健康检查通过: $url -> status=$($h.status) db=$($h.db)" 'OK'
                return $true
            }
            Write-Log "健康检查中... db=$($h.db) status=$($h.status)" 'INFO'
        } catch {
            Write-Log "健康检查未就绪: $($_.Exception.Message)" 'INFO'
        }
        Start-Sleep -Seconds $IntervalSec
    }
    Write-Log "健康检查超时（${TimeoutSec}s）: $url" 'ERROR'
    return $false
}

# 通过 NSSM 管理服务；$Nssm 为 nssm.exe 绝对路径。
function Stop-Service2 {
    param([string]$Nssm, [string]$ServiceName)
    try { & $Nssm stop $ServiceName | Out-Host } catch { Write-Log "停止服务失败/未运行: $($_.Exception.Message)" 'WARN' }
}

function Start-Service2 {
    param([string]$Nssm, [string]$ServiceName)
    try { & $Nssm start $ServiceName | Out-Host } catch { Write-Log "启动服务失败: $($_.Exception.Message)" 'ERROR'; throw }
}

# 解析 JAVA：优先 $env:JAVA_HOME\bin\java.exe，其次 PATH。
function Resolve-Java {
    param([string]$Preferred)
    $candidates = @()
    if ($Preferred) { $candidates += (Join-Path $Preferred 'bin\java.exe') }
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
    foreach ($c in $candidates) {
        if ($c -and (Test-Path $c)) { return (Resolve-Path $c).Path }
    }
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw "找不到 java.exe。请安装 JDK 21 并设置 JAVA_HOME 或在 settings.json 指定 javaHome。"
}
