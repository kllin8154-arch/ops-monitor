#!/bin/bash
# ==========================================
# 运维监控平台 - Linux 安装脚本
# ==========================================

set -e

echo "============================================"
echo "  单服务器一体化运维监控平台 - 安装"
echo "============================================"

# 检查 Java 17
echo "[1/4] 检查 Java 环境..."
if command -v java &> /dev/null; then
    JAVA_VER=$(java -version 2>&1 | head -n 1 | awk -F '"' '{print $2}' | cut -d'.' -f1)
    if [ "$JAVA_VER" -ge 17 ]; then
        echo "✅ Java $JAVA_VER 已安装"
    else
        echo "❌ 需要 Java 17+，当前版本: $JAVA_VER"
        exit 1
    fi
else
    echo "❌ Java 未安装，请安装 Java 17+"
    echo "   Ubuntu: sudo apt install openjdk-17-jdk"
    echo "   CentOS: sudo yum install java-17-openjdk"
    exit 1
fi

# 检查 Docker
echo "[2/4] 检查 Docker 环境..."
if command -v docker &> /dev/null; then
    DOCKER_VER=$(docker --version | awk '{print $3}' | sed 's/,//')
    echo "✅ Docker $DOCKER_VER 已安装"
else
    echo "❌ Docker 未安装"
    echo "   请参考: https://docs.docker.com/engine/install/"
    exit 1
fi

# 检查 Docker 运行状态
if docker info &> /dev/null; then
    echo "✅ Docker 运行中"
else
    echo "⚠️ Docker 未运行，尝试启动..."
    sudo systemctl start docker
    if docker info &> /dev/null; then
        echo "✅ Docker 已启动"
    else
        echo "❌ Docker 启动失败"
        exit 1
    fi
fi

# 检查 docker compose
echo "[3/4] 检查 docker compose..."
if docker compose version &> /dev/null; then
    echo "✅ docker compose (V2) 可用"
elif command -v docker-compose &> /dev/null; then
    echo "✅ docker-compose (V1) 可用"
else
    echo "⚠️ docker compose 不可用，将自动安装..."
    sudo apt-get install -y docker-compose-plugin 2>/dev/null || \
    sudo yum install -y docker-compose-plugin 2>/dev/null || \
    echo "❌ 自动安装失败，请手动安装 docker compose"
fi

# 预拉取镜像（支持离线场景可提前准备）
echo "[4/4] 预拉取 Docker 镜像..."
echo "  拉取 prom/prometheus..."
docker pull prom/prometheus:latest 2>/dev/null || echo "  ⚠️ 拉取失败（离线模式需提前加载）"
echo "  拉取 grafana/grafana..."
docker pull grafana/grafana:latest 2>/dev/null || echo "  ⚠️ 拉取失败"
echo "  拉取 prom/node-exporter..."
docker pull prom/node-exporter:latest 2>/dev/null || echo "  ⚠️ 拉取失败"

echo ""
echo "============================================"
echo "  ✅ 安装检查完成！"
echo "  运行 ./start.sh 启动系统"
echo "============================================"
