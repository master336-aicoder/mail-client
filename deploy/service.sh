#!/usr/bin/env bash
# ============================================================
#  mail-client 服务管理脚本 (Linux)
#  用法: service.sh [start|stop|restart|status]
#
#  支持两种模式：
#    1. systemd（优先）：若存在 mail-client.service 单元
#    2. 直接进程：通过 PID 文件管理 java 进程
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---------- 默认值 ----------
SERVICE_NAME="mail-client"
INSTALL_DIR="/opt/mail-client"
JAR_NAME="mail-client-backend-1.0.0.jar"
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx512m}"
SERVER_PORT=8080
PID_FILE="/var/run/mail-client.pid"
LOG_DIR="/var/log/mail-client"
PROFILE="prod"

# ---------- 尝试读取 settings.json ----------
SETTINGS_FILE="$SCRIPT_DIR/config/settings.json"
if [ -f "$SETTINGS_FILE" ] && command -v python3 &>/dev/null; then
    _read_json() { python3 -c "import json,sys; d=json.load(open('$SETTINGS_FILE')); print(d.get('$1',''))" 2>/dev/null; }
    _v=$(_read_json serviceName);   [ -n "$_v" ] && SERVICE_NAME="$_v"
    _v=$(_read_json installDir);    [ -n "$_v" ] && INSTALL_DIR="$_v"
    _v=$(_read_json serverPort);    [ -n "$_v" ] && SERVER_PORT="$_v"
fi

HEALTH_URL="http://localhost:${SERVER_PORT}/api/health"
MAX_HEALTH_WAIT=30

# ---------- 辅助函数 ----------
info()  { echo "[INFO]  $*"; }
ok()    { echo "[OK]    $*"; }
err()   { echo "[ERROR] $*" >&2; }

has_systemd() { command -v systemctl &>/dev/null && systemctl list-unit-files "${SERVICE_NAME}.service" &>/dev/null 2>&1; }

health_check() {
    local count=0
    info "等待服务就绪..."
    while [ $count -lt $MAX_HEALTH_WAIT ]; do
        if curl -sf "$HEALTH_URL" | grep -q "UP"; then
            ok "服务已启动，健康检查通过"
            return 0
        fi
        count=$((count + 1))
        sleep 2
    done
    err "健康检查超时（${MAX_HEALTH_WAIT}次重试）"
    return 1
}

find_jar() {
    local jar=""
    for d in "$REPO_ROOT/backend/target" "$INSTALL_DIR/app" "$REPO_ROOT/target"; do
        jar=$(ls "$d"/mail-client-backend-*.jar 2>/dev/null | grep -v 'sources\|original' | head -1)
        [ -n "$jar" ] && echo "$jar" && return
    done
    return 1
}

# ---------- 动作 ----------
do_start() {
    if has_systemd; then
        info "通过 systemd 启动 $SERVICE_NAME ..."
        sudo systemctl start "$SERVICE_NAME"
        sleep 2
        health_check
        return
    fi

    # 直接进程模式
    if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
        info "服务已在运行中 (PID $(cat "$PID_FILE"))"
        return
    fi

    local jar
    jar=$(find_jar) || { err "未找到 jar 文件，请先执行 build.sh"; exit 1; }

    [ -d "$LOG_DIR" ] || mkdir -p "$LOG_DIR"

    info "直接启动 $jar ..."
    nohup java $JAVA_OPTS -jar "$jar" \
        --spring.profiles.active="$PROFILE" \
        --server.port="$SERVER_PORT" \
        > "$LOG_DIR/stdout.log" 2> "$LOG_DIR/stderr.log" &
    echo $! > "$PID_FILE"
    ok "进程已启动 (PID $!)"
    health_check
}

do_stop() {
    if has_systemd; then
        info "通过 systemd 停止 $SERVICE_NAME ..."
        sudo systemctl stop "$SERVICE_NAME"
        ok "服务已停止"
        return
    fi

    if [ ! -f "$PID_FILE" ]; then
        info "PID 文件不存在，服务未运行"
        return
    fi
    local pid
    pid=$(cat "$PID_FILE")
    if kill -0 "$pid" 2>/dev/null; then
        info "停止进程 PID $pid ..."
        kill "$pid"
        local count=0
        while kill -0 "$pid" 2>/dev/null && [ $count -lt 15 ]; do
            sleep 1
            count=$((count + 1))
        done
        if kill -0 "$pid" 2>/dev/null; then
            info "进程未响应 SIGTERM，发送 SIGKILL..."
            kill -9 "$pid" 2>/dev/null || true
        fi
        ok "服务已停止"
    else
        info "进程 $pid 已不存在"
    fi
    rm -f "$PID_FILE"
}

do_restart() {
    do_stop
    sleep 1
    do_start
}

do_status() {
    echo "========== 服务状态 =========="
    if has_systemd; then
        systemctl status "$SERVICE_NAME" --no-pager || true
    elif [ -f "$PID_FILE" ]; then
        local pid
        pid=$(cat "$PID_FILE")
        if kill -0 "$pid" 2>/dev/null; then
            echo "  进程运行中 (PID $pid)"
        else
            echo "  PID 文件存在但进程已死 (PID $pid)"
        fi
    else
        echo "  服务未运行（无 systemd 单元，无 PID 文件）"
    fi
    echo ""
    echo "========== 健康检查 =========="
    curl -sf "$HEALTH_URL" 2>/dev/null || echo "  无法连接 $HEALTH_URL"
    echo ""
}

usage() {
    echo "用法: $0 {start|stop|restart|status}"
    echo ""
    echo "  start    启动服务"
    echo "  stop     停止服务"
    echo "  restart  重启服务"
    echo "  status   查看状态 + 健康检查"
    echo ""
    echo "当前配置:"
    echo "  服务名:     $SERVICE_NAME"
    echo "  安装目录:   $INSTALL_DIR"
    echo "  端口:       $SERVER_PORT"
    echo "  Profile:    $PROFILE"
    echo ""
    echo "支持模式:"
    echo "  systemd  — 若存在 ${SERVICE_NAME}.service 单元（推荐生产使用）"
    echo "  PID 文件 — 直接 java 进程，PID 写入 $PID_FILE"
}

# ---------- 入口 ----------
case "${1:-}" in
    start)   do_start ;;
    stop)    do_stop ;;
    restart) do_restart ;;
    status)  do_status ;;
    *)       usage ;;
esac
