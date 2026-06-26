#!/bin/bash
# ==========================================
# 运维监控平台 - Linux 启动脚本
# ==========================================

APP_NAME="ops-monitor"
APP_DIR="$(cd "$(dirname "$0")/.." && pwd)"
JAR_FILE="$APP_DIR/app/target/ops-monitor.jar"
LOG_FILE="$APP_DIR/logs/ops-monitor.log"
PID_FILE="$APP_DIR/ops-monitor.pid"

# 如果 jar 不在 target 下，尝试在当前目录查找
if [ ! -f "$JAR_FILE" ]; then
    JAR_FILE="$APP_DIR/ops-monitor.jar"
fi

if [ ! -f "$JAR_FILE" ]; then
    echo "❌ 找不到 $JAR_FILE"
    echo "  请先执行 mvn package 构建项目"
    exit 1
fi

mkdir -p "$APP_DIR/logs"

case "$1" in
    start)
        if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
            echo "⚠️ $APP_NAME 已在运行 (PID: $(cat "$PID_FILE"))"
            exit 0
        fi
        echo "🚀 启动 $APP_NAME..."
        nohup java -jar "$JAR_FILE" \
            --spring.profiles.active=prod \
            > "$LOG_FILE" 2>&1 &
        echo $! > "$PID_FILE"
        echo "✅ $APP_NAME 已启动 (PID: $(cat "$PID_FILE"))"
        echo "   日志: tail -f $LOG_FILE"
        echo "   管理后台: http://127.0.0.1:8080"
        ;;
    stop)
        if [ -f "$PID_FILE" ]; then
            PID=$(cat "$PID_FILE")
            echo "⏹ 停止 $APP_NAME (PID: $PID)..."
            kill "$PID" 2>/dev/null
            rm -f "$PID_FILE"
            echo "✅ 已停止"
        else
            echo "⚠️ $APP_NAME 未运行"
        fi
        ;;
    restart)
        $0 stop
        sleep 2
        $0 start
        ;;
    status)
        if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
            echo "✅ $APP_NAME 运行中 (PID: $(cat "$PID_FILE"))"
        else
            echo "⏹ $APP_NAME 未运行"
        fi
        ;;
    *)
        # 默认前台启动
        echo "🚀 启动 $APP_NAME (前台模式)..."
        echo "   管理后台: http://127.0.0.1:8080"
        java -jar "$JAR_FILE"
        ;;
esac
