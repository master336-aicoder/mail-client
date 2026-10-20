@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ============================================================
REM  mail-client 本地「生产标准参数」启停脚本 (Windows，无需 NSSM)
REM  对齐 deploy\run-local.ps1 的语义与默认值。
REM  用法: run-local.bat [start^|stop^|restart^|status] [选项]
REM    选项:
REM      --profile dev^|prod     默认 dev（沿用当前 MASTER_KEY，不轮换密钥）
REM      --port 8080             服务端口
REM      --no-scheduling         关闭收信调度（仅调试用）
REM      --jar D:\path\to.jar    指定 jar（默认自动探测 backend\target）
REM      --java-home D:\Java\jdk-21  指定 JDK 根目录
REM  示例:
REM    run-local.bat restart
REM    run-local.bat start --no-scheduling
REM    run-local.bat status --port 8080
REM ============================================================

set "ACTION=start"
set "PROFILE=dev"
set "PORT=8080"
set "SCHEDULING=1"
set "JAR_PATH="
set "JAVA_HOME_OPT="

REM 生产级 JVM 参数（G1GC + 固定堆 + OOM 转储 + UTF-8 + Asia/Shanghai）
set "JAVA_OPTS=-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=logs -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -Djava.security.egd=file:/dev/./urandom"

set "DEPLOY_DIR=%~dp0"
for %%i in ("%DEPLOY_DIR%..") do set "REPO_ROOT=%%~fi"
set "BACKEND_DIR=%REPO_ROOT%\backend"
set "LOG_DIR=%BACKEND_DIR%\logs"
set "HEALTH_TIMEOUT=90"

REM ---------- 解析参数 ----------
set "SEEN_ACTION=0"
:parse
if "%~1"=="" goto :parsed
if "%SEEN_ACTION%"=="0" (
    if "%~1"=="start"   ( set "ACTION=start"   & set "SEEN_ACTION=1" & shift & goto :parse )
    if "%~1"=="stop"    ( set "ACTION=stop"    & set "SEEN_ACTION=1" & shift & goto :parse )
    if "%~1"=="restart" ( set "ACTION=restart" & set "SEEN_ACTION=1" & shift & goto :parse )
    if "%~1"=="status"  ( set "ACTION=status"  & set "SEEN_ACTION=1" & shift & goto :parse )
)
if "%~1"=="--profile"      ( set "PROFILE=%~2"     & shift & shift & goto :parse )
if "%~1"=="--port"         ( set "PORT=%~2"        & shift & shift & goto :parse )
if "%~1"=="--jar"          ( set "JAR_PATH=%~2"    & shift & shift & goto :parse )
if "%~1"=="--java-home"    ( set "JAVA_HOME_OPT=%~2" & shift & shift & goto :parse )
if "%~1"=="--no-scheduling"( set "SCHEDULING=0"    & shift & goto :parse )
echo [WARN] 忽略未知参数: %~1
shift
goto :parse
:parsed

REM ---------- java 解析 ----------
set "JAVA_BIN="
if defined JAVA_HOME_OPT if exist "%JAVA_HOME_OPT%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME_OPT%\bin\java.exe"
if not defined JAVA_BIN if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_BIN (
    for /f "delims=" %%j in ('where java 2^>nul') do if not defined JAVA_BIN set "JAVA_BIN=%%j"
)
if not defined JAVA_BIN (
    echo [ERROR] 找不到 java.exe，请安装 JDK 21 或用 --java-home 指定。
    goto :end
)

if "%ACTION%"=="start"   goto :do_start
if "%ACTION%"=="stop"    goto :do_stop
if "%ACTION%"=="restart" ( call :do_stop & goto :do_start )
if "%ACTION%"=="status"  goto :do_status
goto :usage

REM ---------- 停进程 ----------
:do_stop
set "FOUND=0"
for /f "skip=1 tokens=1" %%p in ('wmic process where "name='java.exe' and commandline like '%%mail-client-backend%%'" get processid 2^>nul') do (
    set "PIDNUM=%%p"
    set "PIDNUM=!PIDNUM: =!"
    if defined PIDNUM (
        if not "!PIDNUM!"=="!PIDNUM:\r=!" set "PIDNUM=!PIDNUM:\r=!"
        if not "!PIDNUM!"=="" (
            echo [STEP] 停止进程 PID !PIDNUM! ...
            taskkill /PID !PIDNUM! /F >nul 2>&1
            set "FOUND=1"
        )
    )
)
if "%FOUND%"=="0" echo [INFO] 未发现运行中的 mail-client 进程
timeout /t 2 /nobreak >nul
goto :end_sub

REM ---------- 启动 ----------
:do_start
if not exist "%LOG_DIR%" mkdir "%LOG_DIR"

REM 解析 jar：优先 --jar，其次 backend\target\mail-client-backend-*.jar
if defined JAR_PATH goto :jar_ready
set "JAR_CAND="
for /f "delims=" %%f in ('dir /b /o-d "%BACKEND_DIR%\target\mail-client-backend-*.jar" 2^>nul') do (
    echo %%f | findstr /i "sources original" >nul
    if errorlevel 1 if not defined JAR_CAND set "JAR_CAND=%%f"
)
if not defined JAR_CAND (
    echo [ERROR] 未找到 mail-client-backend-*.jar，请先执行 package/build 或用 --jar 指定。
    goto :end
)
set "JAR_PATH=%BACKEND_DIR%\target\!JAR_CAND!"
:jar_ready

set "APP_ARGS=--spring.profiles.active=%PROFILE% --server.port=%PORT%"
if "%SCHEDULING%"=="0" set "APP_ARGS=%APP_ARGS% --mail.app.scheduling-enabled=false"

set "STDOUT=%LOG_DIR%\app-%PROFILE%-out.log"
set "STDERR=%LOG_DIR%\app-%PROFILE%-err.log"

echo [STEP] 启动 mail-client
echo   java   : %JAVA_BIN%
echo   jar    : %JAR_PATH%
echo   profile: %PROFILE%  port: %PORT%  scheduling: %SCHEDULING%
echo   log    : %STDOUT%

pushd "%BACKEND_DIR%"
start "mail-client-%PROFILE%" /min cmd /c ""%JAVA_BIN%" %JAVA_OPTS% -jar "%JAR_PATH%" %APP_ARGS% 1>> "%STDOUT%" 2> "%STDERR%""
popd
echo [OK] 已后台拉起，开始健康检查（最长 %HEALTH_TIMEOUT%s）...

call :do_health
goto :end_sub

REM ---------- 健康检查 ----------
:do_health
set /a waited=0
:health_loop
if %waited% geq %HEALTH_TIMEOUT% (
    echo [ERROR] 健康检查超时（%HEALTH_TIMEOUT%s），请查看日志: %STDOUT%
    exit /b 1
)
curl -s --max-time 5 http://localhost:%PORT%/api/health | findstr "\"db\":\"UP\"" >nul
if not errorlevel 1 (
    echo [OK] 服务就绪 (profile=%PROFILE% port=%PORT% scheduling=%SCHEDULING%)
    curl -s http://localhost:%PORT%/api/health
    echo.
    exit /b 0
)
set /a waited+=3
timeout /t 3 /nobreak >nul
goto :health_loop

REM ---------- 状态 ----------
:do_status
echo [INFO] mail-client 进程:
wmic process where "name='java.exe' and commandline like '%%mail-client-backend%%'" get processid,commandline 2^>nul | findstr /i "mail-client" >nul
if errorlevel 1 (
    echo   未运行
) else (
    for /f "skip=1 tokens=1" %%p in ('wmic process where "name='java.exe' and commandline like '%%mail-client-backend%%'" get processid 2^>nul') do (
        echo   PID %%p
    )
)
echo.
echo [INFO] 健康:
curl -s --max-time 5 http://localhost:%PORT%/api/health
echo.
goto :end_sub

:usage
echo 用法: %~nx0 [start^|stop^|restart^|status] [--profile dev^|prod] [--port 8080] [--no-scheduling] [--jar PATH] [--java-home PATH]
goto :end_sub

:end_sub
goto :end

:end
endlocal
