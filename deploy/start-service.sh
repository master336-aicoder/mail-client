#!/usr/bin/env bash
# ============================================================
#  mail-client 直接启动脚本 (Linux，无需 systemd/NSSM)
#  用法: start-service.sh [--dev] [--port 8080] [--background]
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

PROFILE="prod"
PORT=8080
BACKGROUND=false
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx512m}"
PID_FILE="/var/run/mail-client.pid"
LOG_DIR="/var/log/mail-client"

# ---------- 解析参数 ----------
while [ $# -gt 0 ]; do
    case "$1" in
        --dev)         PROFILE="dev"; shift ;;
        --port)        PORT="$2"; shift 2 ;;
        --background)  BACKGROUND=true; shift ;;
        --pid-file)    PID_FILE="$2"; shift 2 ;;
        --log-dir)     LOG_DIR="$2"; shift 2 ;;
        *)             echo "未知参数: $1"; exit 1 ;;
    esac
done

# ---------- 查找 jar ----------
JAR=""
for d in "$REPO_ROOT/backend/target" "$REPO_ROOT/target" "$REPO_ROOT/app"; do
    JAR=$(ls "$d"/mail-client-backend-*.jar 2>/dev/null | grep -v 'sources\|original' | head -1)
    [ -n "$JAR" ] && break
done

if [ -z "$JAR" ]; then
    echo "[ERROR] 未找到 jar 文件，请先执行 build.sh 或 mvn package"
    exit 1
fi

echo "[INFO] 启动 mail-client"
echo "  jar:     $JAR"
echo "  profile: $PROFILE"
echo "  port:    $PORT"
echo ""

if [ "$BACKGROUND" = true ]; then
    [ -d "$LOG_DIR" ] || mkdir -p "$LOG_DIR"
    nohup java $JAVA_OPTS -jar "$JAR" \
        --spring.profiles.active="$PROFILE" \
        --server.port="$PORT" \
        > "$LOG_DIR/stdout.log" 2> "$LOG_DIR/stderr.log" &
    echo $! > "$PID_FILE"
    echo "[OK] 后台启动 (PID $!)"
    echo "  stdout: $LOG_DIR/stdout.log"
    echo "  stderr: $LOG_DIR/stderr.log"
    echo "  PID:    $PID_FILE"
else
    exec java $JAVA_OPTS -jar "$JAR" \
        --spring.profiles.active="$PROFILE" \
        --server.port="$PORT"
fi
