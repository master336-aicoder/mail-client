<#
.SYNOPSIS
    mail-client 快速编译打包脚本（开发/运维通用）。
.DESCRIPTION
    支持以下模式：
      -Full         前端构建 + 拷入 static + Maven 打包（默认）
      -FrontendOnly 仅构建前端
      -BackendOnly  仅 Maven 打包（复用已有 frontend/dist）
      -Dev          mvn spring-boot:run --spring.profiles.active=dev（开发调试）
      -Run          java -jar 启动已打包的 jar（开发调试）
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File deploy\build.ps1
    powershell -ExecutionPolicy Bypass -File deploy\build.ps1 -BackendOnly
    powershell -ExecutionPolicy Bypass -File deploy\build.ps1 -Dev
    powershell -ExecutionPolicy Bypass -File deploy\build.ps1 -Run
#>
[CmdletBinding()]
param(
    [switch]$FrontendOnly,
    [switch]$BackendOnly,
    [switch]$Dev,
    [switch]$Run,
    [string]$JavaHome = 'D:\Java\jdk-21',
    [string]$MavenCmd = 'D:\apache-maven-3.6.3\bin\mvn.cmd',
    [int]$Port = 8080
)

. (Join-Path $PSScriptRoot '_common.ps1')

$RepoRoot   = Split-Path $PSScriptRoot -Parent
$backendDir = Join-Path $RepoRoot 'backend'
$frontendDir= Join-Path $RepoRoot 'frontend'
$staticDir  = Join-Path $backendDir 'src\main\resources\static'
$distDir    = Join-Path $frontendDir 'dist'
$targetDir  = Join-Path $backendDir 'target'

if ($JavaHome) {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome 'bin') + ';' + $env:PATH
}

# ---------- 开发模式：mvn spring-boot:run ----------
if ($Dev) {
    Write-Log '以 dev profile 启动后端（热开发）...' 'STEP'
    Push-Location $backendDir
    try {
        if (-not (Test-Path $MavenCmd)) {
            $mvn = Get-Command mvn -ErrorAction SilentlyContinue
            if (-not $mvn) { throw '找不到 Maven，请设置 -MavenCmd 或将其加入 PATH。' }
            $MavenCmd = $mvn.Source
        }
        & $MavenCmd spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--server.port=$Port"
    } finally { Pop-Location }
    return
}

# ---------- 开发模式：java -jar 启动 ----------
if ($Run) {
    $jar = Get-ChildItem $targetDir -Filter 'mail-client-backend-*.jar' -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notmatch 'sources|original' } | Select-Object -First 1
    if (-not $jar) { throw "未找到已打包 jar: $targetDir，请先执行完整构建。" }
    Write-Log "启动 jar: $($jar.Name) (dev profile, port=$Port)" 'STEP'
    & java -jar $jar.FullName --spring.profiles.active=dev --server.port=$Port
    return
}

# ---------- 前端构建 ----------
if (-not $BackendOnly) {
    Write-Log '构建前端...' 'STEP'
    Push-Location $frontendDir
    try {
        npm ci
        npm run build
    } finally { Pop-Location }
    if (-not (Test-Path (Join-Path $distDir 'index.html'))) {
        throw "前端构建失败，未找到 $distDir\index.html"
    }
    Write-Log '前端构建完成' 'OK'
}

if ($FrontendOnly) { return }

# ---------- dist -> static ----------
Write-Log '将前端产物拷入 backend classpath:/static...' 'STEP'
if (Test-Path $staticDir) { Remove-Item $staticDir -Recurse -Force }
New-Item -ItemType Directory -Path $staticDir -Force | Out-Null
Copy-Item (Join-Path $distDir '*') $staticDir -Recurse -Force

# ---------- Maven 打包 ----------
Write-Log 'Maven 打包后端...' 'STEP'
if (-not (Test-Path $MavenCmd)) {
    $mvn = Get-Command mvn -ErrorAction SilentlyContinue
    if (-not $mvn) { throw '找不到 Maven，请设置 -MavenCmd 或将其加入 PATH。' }
    $MavenCmd = $mvn.Source
}
Push-Location $backendDir
try {
    & $MavenCmd -B clean package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Maven 打包失败，退出码 $LASTEXITCODE" }
} finally { Pop-Location }

$jar = Get-ChildItem $targetDir -Filter 'mail-client-backend-*.jar' |
       Where-Object { $_.Name -notmatch 'sources|original' } | Select-Object -First 1
Write-Log "打包完成: $($jar.FullName)" 'OK'
Write-Log "启动命令: java -jar `"$($jar.FullName)`" --spring.profiles.active=dev" 'STEP'
