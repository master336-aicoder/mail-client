#!/usr/bin/env bash
# ============================================================
#  mail-client 系统巡检脚本 (Linux)
#  用法: inspect.sh [--out-file report.txt]
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---------- 默认值 ----------
SERVICE_NAME="mail-client"
INSTALL_DIR="/opt/mail-client"
SERVER_PORT=8080
DB_HOST="localhost"
DB_PORT=3306
DB_NAME="mail_client"
REDIS_HOST="localhost"
REDIS_PORT=6379
OUT_FILE=""

# ---------- 参数 ----------
while [ $# -gt 0 ]; do
    case "$1" in
        --out-file) OUT_FILE="$2"; shift 2 ;;
        *)          shift ;;
    esac
done

# ---------- 读取 settings.json ----------
SETTINGS_FILE="$SCRIPT_DIR/config/settings.json"
if [ -f "$SETTINGS_FILE" ] && command -v python3 &>/dev/null; then
    _rj() { python3 -c "import json; d=json.load(open('$SETTINGS_FILE')); v=d" ; }
    _v=$(python3 -c "import json; d=json.load(open('$SETTINGS_FILE')); print(d.get('serviceName',''))" 2>/dev/null) && [ -n "$_v" ] && SERVICE_NAME="$_v"
    _v=$(python3 -c "import json; d=json.load(open('$SETTINGS_FILE')); print(d.get('installDir',''))" 2>/dev/null) && [ -n "$_v" ] && INSTALL_DIR="$_v"
    _v=$(python3 -c "import json; d=json.load(open('$SETTINGS_FILE')); print(d.get('serverPort',''))" 2>/dev/null) && [ -n "$_v" ] && SERVER_PORT="$_v"
    _v=$(python3 -c "import json; d=json.load(open('$SETTINGS_FILE')); print(d.get('db',{}).get('host',''))" 2>/dev/null) && [ -n "$_v" ] && DB_HOST="$_v"
    _v=$(python3 -c "import json; d=json.load(open('$SETTINGS_FILE')); print(d.get('db',{}).get('port',''))" 2>/dev/null) && [ -n "$_v" ] && DB_PORT="$_v"
    _v=$(python3 -c "import json; d=json.load(open('$SETTINGS_FILE')); print(d.get('db',{}).get('name',''))" 2>/dev/null) && [ -n "$_v" ] && DB_NAME="$_v"
    _v=$(python3 -c "import json; d=json.load(open('$SETTINGS_FILE')); print(d.get('redis',{}).get('host',''))" 2>/dev/null) && [ -n "$_v" ] && REDIS_HOST="$_v"
    _v=$(python3 -c "import json; d=json.load(open('$SETTINGS_FILE')); print(d.get('redis',{}).get('port',''))" 2>/dev/null) && [ -n "$_v" ] && REDIS_PORT="$_v"
fi

BASE_URL="http://localhost:${SERVER_PORT}"
REPORT=""

add() { REPORT+="$1"$'\n'; }

add "============================================"
add "  mail-client 系统巡检报告"
add "  时间: $(date '+%Y-%m-%d %H:%M:%S')"
add "============================================"
add ""

# 1. 服务状态
add "[1] 服务状态"
if command -v systemctl &>/dev/null && systemctl list-unit-files "${SERVICE_NAME}.service" &>/dev/null 2>&1; then
    add "    管理方式: systemd"
    STATUS=$(systemctl is-active "$SERVICE_NAME" 2>/dev/null || echo "unknown")
    add "    状态: $STATUS"
    ENABLED=$(systemctl is-enabled "$SERVICE_NAME" 2>/dev/null || echo "unknown")
    add "    开机自启: $ENABLED"
elif [ -f "/var/run/mail-client.pid" ]; then
    PID=$(cat /var/run/mail-client.pid)
    if kill -0 "$PID" 2>/dev/null; then
        add "    管理方式: PID 文件"
        add "    状态: 运行中 (PID $PID)"
    else
        add "    管理方式: PID 文件"
        add "    状态: 进程已死 (PID $PID)"
    fi
else
    add "    服务未运行"
fi
add ""

# 2. 端口监听
add "[2] 端口 $SERVER_PORT 监听"
if command -v ss &>/dev/null; then
    LISTEN=$(ss -tlnp 2>/dev/null | grep ":${SERVER_PORT} " || true)
elif command -v netstat &>/dev/null; then
    LISTEN=$(netstat -tlnp 2>/dev/null | grep ":${SERVER_PORT} " || true)
else
    LISTEN=""
fi
if [ -n "$LISTEN" ]; then
    add "    已监听"
    add "    $LISTEN" | sed 's/^/    /'
else
    add "    [异常] 端口 $SERVER_PORT 未被监听"
fi
add ""

# 3. 健康检查
add "[3] 健康检查 ($BASE_URL/api/health)"
HEALTH=$(curl -sf "$BASE_URL/api/health" 2>/dev/null || echo "")
if [ -n "$HEALTH" ]; then
    add "    $HEALTH"
    echo "$HEALTH" | grep -q '"UP"' || add "    [警告] status 非 UP"
else
    add "    [异常] 健康检查失败"
fi
add ""

# 4. 数据库连通
add "[4] 数据库连通 ($DB_HOST:$DB_PORT/$DB_NAME)"
if (echo > /dev/tcp/$DB_HOST/$DB_PORT) 2>/dev/null; then
    add "    TCP 连通"
else
    add "    [异常] TCP 不通"
fi
add ""

# 5. Redis 连通
add "[5] Redis 连通 ($REDIS_HOST:$REDIS_PORT)"
if (echo > /dev/tcp/$REDIS_HOST/$REDIS_PORT) 2>/dev/null; then
    add "    TCP 连通"
else
    add "    [异常] TCP 不通"
fi
add ""

# 6. 磁盘空间
add "[6] 磁盘空间"
df -h "$INSTALL_DIR" 2>/dev/null | tail -1 | while read -r fs size used avail pct mount; do
    add "    $mount  可用 $avail / 共 $size (已用 $pct)"
done
FREE_KB=$(df -k "$INSTALL_DIR" 2>/dev/null | tail -1 | awk '{print $4}')
if [ -n "$FREE_KB" ] && [ "$FREE_KB" -lt 1048576 ] 2>/dev/null; then
    add "    [警告] 磁盘空间不足 1GB"
elif [ -n "$FREE_KB" ] && [ "$FREE_KB" -lt 5242880 ] 2>/dev/null; then
    add "    [警告] 磁盘空间不足 5GB"
fi
add ""

# 7. 日志文件
add "[7] 日志文件"
LOG_DIR="$INSTALL_DIR/logs"
if [ -d "$LOG_DIR" ]; then
    find "$LOG_DIR" -maxdepth 1 -type f -name "*.log" -exec ls -lh {} \; 2>/dev/null | while read -r perms links owner size rest; do
        fname=$(basename "$rest")
        add "    $fname: $size"
    done
    [ -z "$(ls -A "$LOG_DIR" 2>/dev/null)" ] && add "    日志目录为空"
else
    add "    日志目录不存在: $LOG_DIR"
fi
add ""

# 8. 已安装版本
add "[8] 已安装版本"
VER_FILE="$INSTALL_DIR/VERSION.installed.json"
if [ -f "$VER_FILE" ]; then
    cat "$VER_FILE" | while IFS= read -r line; do add "    $line"; done
else
    JAR=$(ls "$INSTALL_DIR/app/"mail-client-backend-*.jar 2>/dev/null | head -1)
    if [ -n "$JAR" ]; then
        SIZE=$(du -h "$JAR" | cut -f1)
        add "    jar: $(basename "$JAR") ($SIZE)"
    else
        add "    未找到安装目录或 jar"
    fi
fi
add ""

# 9. 数据目录
add "[9] 数据目录"
DATA_DIR="$INSTALL_DIR/data"
if [ -d "$DATA_DIR" ]; then
    for sub in ost attachments exports; do
        p="$DATA_DIR/$sub"
        if [ -d "$p" ]; then
            COUNT=$(find "$p" -type f 2>/dev/null | wc -l)
            SIZE=$(du -sh "$p" 2>/dev/null | cut -f1)
            add "    $sub: ${COUNT} 个文件, ${SIZE}"
        fi
    done
else
    add "    数据目录不存在: $DATA_DIR"
fi
add ""

# 10. 备份目录
add "[10] 备份目录"
BACKUP_DIR="$INSTALL_DIR/backups"
if [ -d "$BACKUP_DIR" ]; then
    BACKUP_COUNT=$(find "$BACKUP_DIR" -maxdepth 1 -type d -name "backup-*" | wc -l)
    add "    共 $BACKUP_COUNT 个备份"
    LATEST=$(find "$BACKUP_DIR" -maxdepth 1 -type d -name "backup-*" | sort | tail -1)
    [ -n "$LATEST" ] && add "    最近备份: $(basename "$LATEST")"
else
    add "    备份目录不存在"
fi
add ""

add "============================================"
add "  巡检完毕"
add "============================================"

echo "$REPORT"

if [ -n "$OUT_FILE" ]; then
    mkdir -p "$(dirname "$OUT_FILE")" 2>/dev/null || true
    echo "$REPORT" > "$OUT_FILE"
    echo "[OK] 报告已保存: $OUT_FILE"
fi
