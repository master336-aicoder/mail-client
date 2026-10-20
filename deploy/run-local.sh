#!/usr/bin/env bash
# ============================================================
#  mail-client 本地「生产标准参数」启动脚本 (Linux，无需 systemd)
#  与 run-local.ps1 / run-local.bat 行为对齐：自动停旧实例、后台拉起、
#  轮询 /api/health 直至就绪。默认 profile=dev（沿用现有 MASTER_KEY，不轮换密钥）。
#
#  用法:
#    ./run-local.sh start
#    ./run-local.sh start --no-scheduling        # 仅提供服务，不跑周期收信任务
#    ./run-local.sh restart --profile prod --port 8080
#    ./run-local.sh status
#    ./run-local.sh stop
#
#  可选参数: --profile NAME | --port N | --jar PATH | --java-home DIR |
#            --java-opts "..." | --no-scheduling | --health-timeout N | --tail N
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=_common.sh
source "$SCRIPT_DIR/_common.sh"

REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKEND_DIR="$REPO_ROOT/backend"

# ---------- 默认参数（与 ps1/bat 一致）----------
ACTION="start"
PROFILE="dev"
PORT=8080
SCHEDULING=true
JAR_PATH=""
JAVA_HOME_PREF=""
HEALTH_TIMEOUT=90
TAIL=0
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=logs -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai -Djava.security.egd=file:/dev/./urandom}"

# ---------- 解析参数 ----------
while [ $# -gt 0 ]; do
    case "$1" in
        start|stop|restart|status) ACTION="$1"; shift ;;
        --profile)        PROFILE="$2"; shift 2 ;;
        --port)           PORT="$2"; shift 2 ;;
        --jar)            JAR_PATH="$2"; shift 2 ;;
        --java-home)      JAVA_HOME_PREF="$2"; shift 2 ;;
        --java-opts)      JAVA_OPTS="$2"; shift 2 ;;
        --no-scheduling)  SCHEDULING=false; shift ;;
        --health-timeout) HEALTH_TIMEOUT="$2"; shift 2 ;;
        --tail)           TAIL="$2"; shift 2 ;;
        -h|--help)        sed -n '2,20p' "$0"; exit 0 ;;
        *)                die "未知参数: $1" ;;
    esac
done

LOG_DIR="$BACKEND_DIR/logs"
STDOUT_LOG="$LOG_DIR/app-$PROFILE.log"
STDERR_LOG="$LOG_DIR/app-$PROFILE.err.log"
PID_FILE="$LOG_DIR/run-local.pid"

# ---------- 查找 jar ----------
find_jar() {
    if [ -n "$JAR_PATH" ]; then
        [ -f "$JAR_PATH" ] || die "指定的 jar 不存在: $JAR_PATH"
        echo "$JAR_PATH"; return
    fi
    for d in "$BACKEND_DIR/target" /opt/mail-client/app "$REPO_ROOT/app"; do
        [ -d "$d" ] || continue
        local jar
        jar=$(ls "$d"/mail-client-backend-*.jar 2>/dev/null | grep -v 'sources\|original' | tail -1 || true)
        [ -n "$jar" ] && { echo "$jar"; return; }
    done
    die "未找到 mail-client-backend-*.jar，请先执行 deploy/package.sh 或用 --jar 指定。"
}

# ---------- 运行中的 PID（jar 命令行匹配；PID 文件优先）----------
running_pids() {
    local pids=""
    if [ -f "$PID_FILE" ]; then
        local p; p=$(cat "$PID_FILE" 2>/dev/null || true)
        if [ -n "$p" ] && [ -d "/proc/$p" ]; then pids="$p"; fi
    fi
    local found
    found=$(pgrep -f "java.*mail-client-backend-[^ ]*\.jar" 2>/dev/null || true)
    for f in $found; do
        case " $pids " in *" $f "*) ;; *) pids="$pids $f" ;; esac
    done
    echo $pids
}

stop_running() {
    local pids; pids=$(running_pids)
    if [ -z "$pids" ]; then info "未发现运行中的 mail-client 进程"; return; fi
    for p in $pids; do
        step "停止进程 PID $p ..."
        kill "$p" 2>/dev/null || true
    done
    # 优雅退出，超时后强杀
    local waited=0
    while [ $waited -lt 15 ] && [ -n "$(running_pids)" ]; do sleep 1; waited=$((waited + 1)); done
    pids=$(running_pids)
    for p in $pids; do warn "PID $p 未退出，强制 kill -9"; kill -9 "$p" 2>/dev/null || true; done
    rm -f "$PID_FILE"
    sleep 1
}

start_app() {
    mkdir -p "$LOG_DIR"
    local jar java
    jar=$(find_jar)
    java=$(resolve_java "$JAVA_HOME_PREF") || die "未找到可用 java，请用 --java-home 指定 JDK21。"
    if ! check_java_21 "$java"; then
        warn "java 版本可能低于 21（$java），继续尝试。"
    fi

    local app_args=(--spring.profiles.active="$PROFILE" --server.port="$PORT")
    [ "$SCHEDULING" = false ] && app_args+=(--mail.app.scheduling-enabled=false)

    step "启动: $java"
    info "JVM : $JAVA_OPTS"
    info "APP : $jar ${app_args[*]}"

    # shellcheck disable=SC2086
    ( cd "$BACKEND_DIR" && nohup $java $JAVA_OPTS -jar "$jar" "${app_args[@]}" \
        >"$STDOUT_LOG" 2>"$STDERR_LOG" & echo $! > "$PID_FILE" )
    local pid; pid=$(cat "$PID_FILE")
    ok "进程已拉起 PID $pid，日志: $STDOUT_LOG"

    if health_check "http://localhost:$PORT" "$HEALTH_TIMEOUT" 3; then
        ok "服务就绪 (profile=$PROFILE, scheduling=$SCHEDULING, port=$PORT)"
    else
        err "健康检查未通过，请查看日志排障。"
        [ "$TAIL" -gt 0 ] && tail -n "$TAIL" "$STDOUT_LOG" || true
        die "启动失败：/api/health 在 ${HEALTH_TIMEOUT}s 内未就绪"
    fi
}

show_status() {
    local pids; pids=$(running_pids)
    if [ -n "$pids" ]; then ok "运行中 PID: $pids"; else warn "未运行（无 mail-client java 进程）"; fi
    local body
    body=$(curl -sf --max-time 5 "http://localhost:$PORT/api/health" 2>/dev/null || true)
    if [ -n "$body" ]; then info "健康: $body"; else warn "无法访问 /api/health"; fi
    [ "$TAIL" -gt 0 ] && [ -f "$STDOUT_LOG" ] && tail -n "$TAIL" "$STDOUT_LOG" || true
}

case "$ACTION" in
    start)   start_app ;;
    stop)    stop_running ;;
    restart) stop_running; start_app ;;
    status)  show_status ;;
    *)       die "未知动作: $ACTION（应为 start|stop|restart|status）" ;;
esac
