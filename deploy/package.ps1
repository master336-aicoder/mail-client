<#
.SYNOPSIS
    mail-client 制品打包脚本（Windows / PowerShell）。
.DESCRIPTION
    1) 构建前端 (npm ci + npm run build) -> frontend/dist
    2) 将 dist 拷入 backend/src/main/resources/static 以支持单进程托管
    3) Maven 打包后端 -> backend/target/mail-client-backend-<ver>.jar（含前端）
    4) 组装带版本清单的可分发目录并压缩为 zip
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File deploy\package.ps1 -Version 1.0.0
    powershell -ExecutionPolicy Bypass -File deploy\package.ps1 -Version 1.0.1 -SkipFrontend
#>
[CmdletBinding()]
param(
    [string]$Version = '1.0.0',
    [string]$RepoRoot = '',
    [string]$JavaHome = 'D:\Java\jdk-21',
    [string]$MavenCmd = 'D:\apache-maven-3.6.3\bin\mvn.cmd',
    [string]$OutDir = '',                       # 产物输出目录，默认 <repo>\dist-release
    [switch]$SkipFrontend,                      # 复用已存在的 frontend/dist
    [bool]$SkipTests = $true                    # 默认跳过测试以加速打包；-SkipTests:$false 关闭
)

. (Join-Path $PSScriptRoot '_common.ps1')

if (-not $RepoRoot) { $RepoRoot = Split-Path $PSScriptRoot -Parent }
$backendDir  = Join-Path $RepoRoot 'backend'
$frontendDir = Join-Path $RepoRoot 'frontend'
$staticDir   = Join-Path $backendDir 'src\main\resources\static'
$targetDir   = Join-Path $backendDir 'target'
if (-not $OutDir) { $OutDir = Join-Path $RepoRoot 'dist-release' }
$releaseName = "mail-client-$Version"
$releaseDir  = Join-Path $OutDir $releaseName

Write-Log "开始打包 mail-client 版本 $Version" 'STEP'
Write-Log "仓库根: $RepoRoot"

# ---------- 1. 前端构建 ----------
if (-not $SkipFrontend) {
    Write-Log '构建前端 (npm ci + npm run build)...' 'STEP'
    Push-Location $frontendDir
    try {
        npm ci
        npm run build
    } finally { Pop-Location }
} else {
    Write-Log '跳过前端构建，复用 frontend/dist' 'WARN'
}
$distDir = Join-Path $frontendDir 'dist'
if (-not (Test-Path (Join-Path $distDir 'index.html'))) {
    throw "未找到前端构建产物 $distDir\index.html，请先执行不带 -SkipFrontend 的打包。"
}

# ---------- 2. dist -> backend static ----------
Write-Log '将前端产物拷入 backend classpath:/static 以单进程托管...' 'STEP'
if (Test-Path $staticDir) { Remove-Item $staticDir -Recurse -Force }
New-Item -ItemType Directory -Path $staticDir -Force | Out-Null
Copy-Item (Join-Path $distDir '*') $staticDir -Recurse -Force

# ---------- 3. Maven 打包 ----------
Write-Log 'Maven 打包后端 (含前端静态资源)...' 'STEP'
if ($JavaHome) {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome 'bin') + ';' + $env:PATH
}
if (-not (Test-Path $MavenCmd)) {
    $mvn = Get-Command mvn -ErrorAction SilentlyContinue
    if (-not $mvn) { throw "找不到 Maven，请设置 -MavenCmd 或将其加入 PATH。" }
    $MavenCmd = $mvn.Source
}
Push-Location $backendDir
try {
    $mvnArgs = @('-B', 'clean', 'package')
    if ($SkipTests) { $mvnArgs += '-DskipTests' }
    & $MavenCmd @mvnArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven 打包失败，退出码 $LASTEXITCODE" }
} finally { Pop-Location }

$jarName = "mail-client-backend-$Version.jar"
$jarPath = Join-Path $targetDir $jarName
if (-not (Test-Path $jarPath)) {
    # 容错：按模式匹配
    $found = Get-ChildItem $targetDir -Filter 'mail-client-backend-*.jar' | Where-Object { $_.Name -notmatch 'sources|original' } | Select-Object -First 1
    if (-not $found) { throw "未在后端 target 目录找到可执行 jar。" }
    $jarPath = $found.FullName; $jarName = $found.Name
}
Write-Log "后端 jar: $jarPath" 'OK'

# ---------- 4. 组装分发目录 ----------
Write-Log "组装发布目录: $releaseDir" 'STEP'
if (Test-Path $releaseDir) { Remove-Item $releaseDir -Recurse -Force }
$null = New-Item -ItemType Directory -Path (Join-Path $releaseDir 'app')
$null = New-Item -ItemType Directory -Path (Join-Path $releaseDir 'scripts')
$null = New-Item -ItemType Directory -Path (Join-Path $releaseDir 'config')
$null = New-Item -ItemType Directory -Path (Join-Path $releaseDir 'docs')

Copy-Item $jarPath (Join-Path $releaseDir "app\$jarName") -Force
# 脚本与模板一并入包，使发布包自包含
Copy-Item (Join-Path $PSScriptRoot '*.ps1') (Join-Path $releaseDir 'scripts') -Force
Copy-Item (Join-Path $PSScriptRoot 'config\*') (Join-Path $releaseDir 'config') -Recurse -Force
if (Test-Path (Join-Path $PSScriptRoot 'nssm.exe')) { Copy-Item (Join-Path $PSScriptRoot 'nssm.exe') (Join-Path $releaseDir 'scripts') }
if (Test-Path (Join-Path $RepoRoot 'DEPLOY.md')) { Copy-Item (Join-Path $RepoRoot 'DEPLOY.md') (Join-Path $releaseDir 'docs') }

# 版本清单
$jarSha = (Get-FileHash $jarPath -Algorithm SHA256).Hash
$gitSha = ''
try { $gitSha = (& git -C $RepoRoot rev-parse --short HEAD 2>$null) } catch {}
$manifest = [ordered]@{
    name       = 'mail-client'
    version    = $Version
    buildTime  = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    gitCommit  = "$gitSha"
    jar        = "app\$jarName"
    jarSha256  = $jarSha
    frontend   = 'bundled-in-jar (classpath:/static)'
}
$manifest | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $releaseDir 'VERSION.json') -Encoding UTF8

# 压缩
$zipPath = Join-Path $OutDir "$releaseName.zip"
if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
Compress-Archive -Path $releaseDir -DestinationPath $zipPath

Write-Log "打包完成:" 'OK'
Write-Log "  目录: $releaseDir"
Write-Log "  压缩包: $zipPath"
Write-Log "  jar SHA256: $jarSha"
Write-Log '下一步：将 zip 上传到目标服务器，解压后在 scripts 目录运行 install.ps1' 'STEP'
