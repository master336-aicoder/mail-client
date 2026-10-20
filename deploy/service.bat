@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ============================================================
REM  mail-client 服务管理脚本 (Windows)
REM  用法: service.bat [start|stop|restart|status]
REM ============================================================

set "ACTION=%~1"
set "SERVICE_NAME=mail-client"
set "INSTALL_DIR=C:\mail-client"
set "JAR_NAME=mail-client-backend-1.0.0.jar"
set "JAVA_OPTS=-Xms256m -Xmx512m"
set "SERVER_PORT=8080"

REM 尝试从 settings.json 读取配置
if exist "%~dp0config\settings.json" (
    for /f "usebackq tokens=*" %%i in (`powershell -Command "(Get-Content '%~dp0config\settings.json' | ConvertFrom-Json).serviceName"`) do set "SERVICE_NAME=%%i"
    for /f "usebackq tokens=*" %%i in (`powershell -Command "(Get-Content '%~dp0config\settings.json' | ConvertFrom-Json).installDir"`) do set "INSTALL_DIR=%%i"
    for /f "usebackq tokens=*" %%i in (`powershell -Command "(Get-Content '%~dp0config\settings.json' | ConvertFrom-Json).serverPort"`) do set "SERVER_PORT=%%i"
)

if "%ACTION%"=="" goto :usage
if "%ACTION%"=="start" goto :start
if "%ACTION%"=="stop" goto :stop
if "%ACTION%"=="restart" goto :restart
if "%ACTION%"=="status" goto :status
goto :usage

:start
echo [INFO] 启动服务 %SERVICE_NAME% ...
nssm status %SERVICE_NAME% 2>nul | findstr "SERVICE_RUNNING" >nul
if %errorlevel%==0 (
    echo [INFO] 服务已在运行中
    goto :health
)
nssm start %SERVICE_NAME%
timeout /t 3 /nobreak >nul
goto :health

:stop
echo [INFO] 停止服务 %SERVICE_NAME% ...
nssm stop %SERVICE_NAME%
timeout /t 2 /nobreak >nul
echo [OK] 服务已停止
goto :end

:restart
echo [INFO] 重启服务 %SERVICE_NAME% ...
nssm restart %SERVICE_NAME%
timeout /t 5 /nobreak >nul
goto :health

:status
echo [INFO] 服务状态:
nssm status %SERVICE_NAME%
echo.
echo [INFO] 健康检查:
curl -s http://localhost:%SERVER_PORT%/api/health
echo.
goto :end

:health
echo [INFO] 等待服务就绪...
set /a count=0
:health_loop
if %count% geq 30 (
    echo [ERROR] 健康检查超时
    goto :end
)
curl -s http://localhost:%SERVER_PORT%/api/health | findstr "UP" >nul
if %errorlevel%==0 (
    echo [OK] 服务已启动，健康检查通过
    goto :end
)
set /a count+=1
timeout /t 2 /nobreak >nul
goto :health_loop

:usage
echo 用法: %~nx0 [start^|stop^|restart^|status]
echo.
echo   start    启动服务
echo   stop     停止服务
echo   restart  重启服务
echo   status   查看状态
echo.
echo 当前配置:
echo   服务名: %SERVICE_NAME%
echo   安装目录: %INSTALL_DIR%
echo   端口: %SERVER_PORT%
goto :end

:end
endlocal
