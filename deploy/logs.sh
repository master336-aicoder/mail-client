#!/usr/bin/env bash
# ============================================================
#  mail-client 日志查看脚本 (Linux)
#  用法: logs.sh [--tail 50] [--follow] [--filter ERROR] [--stdout]
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

INSTALL_DIR="/opt/mail-client"
TAIL_LINES=50
FOLLOW=false
FILTER=""
LOG_NAME="stderr.log"

# ---------- 读取 settings.json ----------
SETTINGS_FILE="$SCRIPT_DIR/config/settings.json"
if [ -f "$SETTINGS_FILE" ] && command -v python3 &>/dev/null; then
    _v=$(python3 -c "import json; d=json.load(open('$SETTINGS_FILE')); print(d.get('installDir',''))" 2>/dev/null)
    [ -n "$_v" ] && INSTALL_DIR="$_v"
fi

# ---------- 参数 ----------
while [ $# -gt 0 ]; do
    case "$1" in
        --tail)    TAIL_LINES="$2"; shift 2 ;;
        --follow)  FOLLOW=true; shift ;;
        --filter)  FILTER="$2"; shift 2 ;;
        --stdout)  LOG_NAME="stdout.log"; shift ;;
        *)         echo "未知参数: $1"; exit 1 ;;
    esac
done

LOG_FILE="$INSTALL_DIR/logs/$LOG_NAME"

if [ ! -f "$LOG_FILE" ]; then
    echo "[ERROR] 日志文件不存在: $LOG_FILE"
    echo "[INFO]  请确认安装目录配置正确，或尝试 --stdout 查看 stdout 日志。"
    exit 1
fi

FILE_SIZE=$(du -h "$LOG_FILE" | cut -f1)
echo "[INFO] 日志文件: $LOG_FILE (${FILE_SIZE})"

if [ "$FOLLOW" = true ]; then
    echo "[INFO] 实时跟踪中... (Ctrl+C 退出)"
    tail -n "$TAIL_LINES" -f "$LOG_FILE"
elif [ -n "$FILTER" ]; then
    echo "[INFO] 过滤关键字: $FILTER"
    grep --color=auto -- "$FILTER" "$LOG_FILE" || echo "(无匹配)"
else
    tail -n "$TAIL_LINES" "$LOG_FILE"
fi
