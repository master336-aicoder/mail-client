@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ============================================================
REM  mail-client 直接启动脚本 (Windows，无需 NSSM)
REM  用法: start-service.bat [--dev] [--port 8080]
REM ============================================================

set "PROFILE=prod"
set "PORT=8080"
set "JAVA_OPTS=-Xms256m -Xmx512m"
set "JAR_DIR=%~dp0.."
set "JAR_NAME="

REM 解析参数
:parse_args
if "%~1"=="" goto :args_done
if "%~1"=="--dev" (
    set "PROFILE=dev"
    shift
    goto :parse_args
)
if "%~1"=="--port" (
    set "PORT=%~2"
    shift
    shift
    goto :parse_args
)
shift
goto :parse_args
:args_done

REM 查找 jar 文件
if exist "%JAR_DIR%\target\mail-client-backend-*.jar" (
    for %%f in ("%JAR_DIR%\target\mail-client-backend-*.jar") do (
        echo %%~nf | findstr /v "sources original" >nul
        if !errorlevel!==0 set "JAR_NAME=%%f"
    )
)
if exist "%JAR_DIR%\backend\target\mail-client-backend-*.jar" (
    for %%f in ("%JAR_DIR%\backend\target\mail-client-backend-*.jar") do (
        echo %%~nf | findstr /v "sources original" >nul
        if !errorlevel!==0 set "JAR_NAME=%%f"
    )
)

if "%JAR_NAME%"=="" (
    echo [ERROR] 未找到 jar 文件，请先执行 build.bat 或 mvn package
    goto :end
)

echo [INFO] 启动 mail-client
echo   jar: %JAR_NAME%
echo   profile: %PROFILE%
echo   port: %PORT%
echo.

java %JAVA_OPTS% -jar "%JAR_NAME%" --spring.profiles.active=%PROFILE% --server.port=%PORT%

:end
endlocal
