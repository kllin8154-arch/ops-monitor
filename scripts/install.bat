@echo off
chcp 65001 >nul 2>&1
echo ============================================
echo   单服务器一体化运维监控平台 - Windows 安装
echo ============================================

echo [1/3] 检查 Java 环境...
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo ❌ Java 未安装，请安装 Java 17+
    echo    下载: https://adoptium.net/
    pause
    exit /b 1
)
echo ✅ Java 已安装

echo [2/3] 检查 Docker 环境...
docker --version >nul 2>&1
if %errorlevel% neq 0 (
    echo ❌ Docker 未安装
    echo    请安装 Docker Desktop: https://www.docker.com/products/docker-desktop
    pause
    exit /b 1
)
echo ✅ Docker 已安装

docker info >nul 2>&1
if %errorlevel% neq 0 (
    echo ⚠️ Docker 未运行，请启动 Docker Desktop
    pause
    exit /b 1
)
echo ✅ Docker 运行中

echo [3/3] 预拉取 Docker 镜像...
docker pull prom/prometheus:latest
docker pull grafana/grafana:latest
docker pull prom/node-exporter:latest

echo.
echo ============================================
echo   ✅ 安装检查完成！
echo   运行 start.bat 启动系统
echo ============================================
pause
