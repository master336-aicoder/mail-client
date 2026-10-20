@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ============================================================
REM  mail-client 编译打包脚本 (Windows)
REM  用法: build.bat [--frontend-only] [--backend-only] [--skip-tests]
REM ============================================================

set "REPO_ROOT=%~dp0.."
set "BACKEND_DIR=%REPO_ROOT%\backend"
set "FRONTEND_DIR=%REPO_ROOT%\frontend"
set "STATIC_DIR=%BACKEND_DIR%\src\main\resources\static"
set "JAVA_HOME=D:\Java\jdk-21"
set "MVN_CMD=D:\apache-maven-3.6.3\bin\mvn.cmd"
set "SKIP_TESTS=true"
set "MODE=full"

REM 解析参数
:parse_args
if "%~1"=="" goto :args_done
if "%~1"=="--frontend-only" (
    set "MODE=frontend"
    shift
    goto :parse_args
)
if "%~1"=="--backend-only" (
    set "MODE=backend"
    shift
    goto :parse_args
)
if "%~1"=="--skip-tests" (
    set "SKIP_TESTS=true"
    shift
    goto :parse_args
)
if "%~1"=="--no-skip-tests" (
    set "SKIP_TESTS=false"
    shift
    goto :parse_args
)
shift
goto :parse_args
:args_done

set "PATH=%JAVA_HOME%\bin;%PATH%"

echo [STEP] Java 版本:
java -version
echo.

REM 前端构建
if "%MODE%"=="backend" goto :skip_frontend
echo [STEP] 构建前端...
pushd "%FRONTEND_DIR%"
call npm ci
if %errorlevel% neq 0 (
    echo [ERROR] npm ci 失败
    popd
    goto :end
)
call npm run build
if %errorlevel% neq 0 (
    echo [ERROR] npm run build 失败
    popd
    goto :end
)
popd
echo [OK] 前端构建完成
if "%MODE%"=="frontend" goto :end

REM 拷贝 dist 到 static
:skip_frontend
echo [STEP] 拷贝前端产物到 backend static...
if exist "%STATIC_DIR%" rmdir /s /q "%STATIC_DIR%"
mkdir "%STATIC_DIR%"
xcopy /e /q "%FRONTEND_DIR%\dist\*" "%STATIC_DIR%\"
echo [OK] 拷贝完成

if "%MODE%"=="frontend" goto :end

REM Maven 打包
echo [STEP] Maven 打包后端...
pushd "%BACKEND_DIR%"
set "MVN_ARGS=-B clean package"
if "%SKIP_TESTS%"=="true" set "MVN_ARGS=%MVN_ARGS% -DskipTests"
call "%MVN_CMD%" %MVN_ARGS%
if %errorlevel% neq 0 (
    echo [ERROR] Maven 打包失败
    popd
    goto :end
)
popd

echo.
echo [OK] 打包完成
echo   jar: %BACKEND_DIR%\target\mail-client-backend-1.0.0.jar
echo.
echo [INFO] 启动命令:
echo   java -jar "%BACKEND_DIR%\target\mail-client-backend-1.0.0.jar" --spring.profiles.active=dev
goto :end

:end
endlocal
