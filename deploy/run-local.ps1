<#
.SYNOPSIS
    mail-client 本地「生产标准参数」前台/后台启动脚本（不依赖 NSSM 服务）。
.DESCRIPTION
    以生产级 JVM 参数（G1GC + 固定堆 + OOM 转储 + UTF-8 + Asia/Shanghai 时区）启动打包好的 jar，
    自动停掉同 jar 的旧实例、后台拉起、轮询 /api/health 直至就绪。
    默认 profile=dev（沿用当前 MASTER_KEY，不轮换密钥，真实邮箱凭据仍可解密）。
    若确需真 prod：先迁移真实 JWT_SECRET/MASTER_KEY 环境变量，再 -Profile prod。
    默认开启收信调度（会真实轮询所有 enabled 账户，含外部邮箱）；本地调试用 -Scheduling:$false 关闭。
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File deploy\run-local.ps1 start
    powershell -ExecutionPolicy Bypass -File deploy\run-local.ps1 start -Scheduling:$false
    powershell -ExecutionPolicy Bypass -File deploy\run-local.ps1 restart -Profile prod -Port 8080
    powershell -ExecutionPolicy Bypass -File deploy\run-local.ps1 status
    powershell -ExecutionPolicy Bypass -File deploy\run-local.ps1 stop
#>
[CmdletBinding()]
param(
    [Parameter(Position=0)]
    [ValidateSet('start','stop','restart','status')]
    [string]$Action = 'start',

    [string]$Profile = 'dev',
    [int]$Port = 8080,

    # 收信调度开关：$true=启用（真实轮询外部邮箱），$false=仅提供服务不跑周期任务
    [bool]$Scheduling = $true,

    # 生产级 JVM 参数默认值，可用 -JavaOpts 覆盖
    [string]$JavaOpts = '-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 ' +
                        '-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=logs ' +
                        '-XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 ' +
                        '-Duser.timezone=Asia/Shanghai -Djava.security.egd=file:/dev/./urandom',

    # jar 路径留空则自动探测 backend/target 与安装目录
    [string]$JarPath = '',
    [string]$JavaHome = '',
    [int]$HealthTimeoutSec = 90,
    [int]$Tail = 0
)

. (Join-Path $PSScriptRoot '_common.ps1')

$RepoRoot   = Split-Path $PSScriptRoot -Parent
$BackendDir = Join-Path $RepoRoot 'backend'
$LogDir     = Join-Path $BackendDir 'logs'
$StdoutLog  = Join-Path $LogDir "app-$Profile.log"
$StderrLog  = Join-Path $LogDir "app-$Profile.err.log"

function Find-Jar {
    if ($JarPath) {
        if (-not (Test-Path $JarPath)) { throw "指定的 jar 不存在: $JarPath" }
        return (Resolve-Path $JarPath).Path
    }
    foreach ($d in @((Join-Path $BackendDir 'target'), 'C:\mail-client\app')) {
        if (-not (Test-Path $d)) { continue }
        $jar = Get-ChildItem -Path $d -Filter 'mail-client-backend-*.jar' -ErrorAction SilentlyContinue |
               Where-Object { $_.Name -notmatch 'sources|original' } |
               Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if ($jar) { return $jar.FullName }
    }
    throw "未找到 mail-client-backend-*.jar，请先执行 deploy\package.ps1 或用 -JarPath 指定。"
}

function Get-RunningPids {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*mail-client-backend*' } |
        ForEach-Object { $_.ProcessId }
}

function Stop-Running {
    $pids = @(Get-RunningPids)
    if ($pids.Count -eq 0) { Write-Log '未发现运行中的 mail-client 进程' 'INFO'; return }
    foreach ($p in $pids) {
        Write-Log "停止进程 PID $p ..." 'STEP'
        Stop-Process -Id $p -Force -ErrorAction SilentlyContinue
    }
    Start-Sleep -Seconds 3
}

function Start-App {
    if (-not (Test-Path $LogDir)) { New-Item -ItemType Directory -Path $LogDir -Force | Out-Null }
    $jar  = Find-Jar
    $java = Resolve-Java -Preferred $JavaHome
    $appArgs = @('--spring.profiles.active=' + $Profile, '--server.port=' + $Port)
    if (-not $Scheduling) { $appArgs += '--mail.app.scheduling-enabled=false' }

    # JVM 参数在前、-jar 与 jar 路径居中、应用参数在后
    $argList = @()
    if ($JavaOpts) { $argList += ($JavaOpts -split '\s+' | Where-Object { $_ }) }
    $argList += @('-jar', $jar)
    $argList += $appArgs

    Write-Log "启动: $java" 'STEP'
    Write-Log "JVM : $JavaOpts" 'INFO'
    Write-Log "APP : $($argList -join ' ')" 'INFO'
    $proc = Start-Process -FilePath $java -ArgumentList $argList `
        -WorkingDirectory $BackendDir `
        -RedirectStandardOutput $StdoutLog -RedirectStandardError $StderrLog `
        -WindowStyle Hidden -PassThru
    Write-Log "进程已拉起 PID $($proc.Id)，日志: $StdoutLog" 'OK'

    if (Invoke-HealthCheck -BaseUrl "http://localhost:$Port" -TimeoutSec $HealthTimeoutSec -IntervalSec 3) {
        Write-Log "服务就绪 (profile=$Profile, scheduling=$Scheduling, port=$Port)" 'OK'
    } else {
        Write-Log '健康检查未通过，请查看日志排障。' 'ERROR'
        if ($Tail -gt 0) { Get-Content $StdoutLog -Tail $Tail | Write-Host }
        throw "启动失败：/api/health 在 ${HealthTimeoutSec}s 内未就绪"
    }
}

function Show-Status {
    $pids = @(Get-RunningPids)
    if ($pids.Count -gt 0) { Write-Log "运行中 PID: $($pids -join ', ')" 'OK' }
    else { Write-Log '未运行（无 mail-client java 进程）' 'WARN' }
    try {
        $r = Invoke-RestMethod -Uri "http://localhost:$Port/api/health" -TimeoutSec 5
        $h = if ($r.data) { $r.data } else { $r }
        Write-Log "健康: status=$($h.status) db=$($h.db) storage=$($h.storage)" 'INFO'
    } catch {
        Write-Log "无法访问 /api/health：$($_.Exception.Message)" 'WARN'
    }
    if ($Tail -gt 0 -and (Test-Path $StdoutLog)) { Get-Content $StdoutLog -Tail $Tail | Write-Host }
}

switch ($Action) {
    'start'   { Start-App }
    'stop'    { Stop-Running }
    'restart' { Stop-Running; Start-App }
    'status'  { Show-Status }
}
