@echo off
chcp 65001 >nul 2>&1
echo ============================================
echo   运维监控平台 - 启动
echo ============================================

set APP_DIR=%~dp0..
set JAR_FILE=%APP_DIR%\app\target\ops-monitor.jar

if not exist "%JAR_FILE%" (
    set JAR_FILE=%APP_DIR%\ops-monitor.jar
)

if not exist "%JAR_FILE%" (
    echo ❌ 找不到 ops-monitor.jar
    echo    请先执行 mvn package 构建项目
    pause
    exit /b 1
)

echo 🚀 启动运维监控平台...
echo    管理后台: http://127.0.0.1:8080
echo    按 Ctrl+C 停止
echo.

java -jar "%JAR_FILE%"
pause
