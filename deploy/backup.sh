#!/usr/bin/env bash
# ============================================================
#  mail-client 数据备份脚本 (Linux)
#  用法: backup.sh [--retention 7] [--skip-attachments] [--skip-db]
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ---------- 默认值 ----------
INSTALL_DIR="/opt/mail-client"
BACKUP_ROOT=""
RETENTION=7
SKIP_ATTACHMENTS=false
SKIP_DB=false

DB_HOST="localhost"
DB_PORT=3306
DB_NAME="mail_client"
DB_USER="root"
DB_PASS=""

# ---------- 读取 settings.json ----------
SETTINGS_FILE="$SCRIPT_DIR/config/settings.json"
if [ -f "$SETTINGS_FILE" ] && command -v python3 &>/dev/null; then
    _rj() { python3 -c "import json; d=json.load(open('$SETTINGS_FILE')); print($1)" 2>/dev/null; }
    _v=$(_rj "d.get('installDir','')");    [ -n "$_v" ] && INSTALL_DIR="$_v"
    _v=$(_rj "d.get('db',{}).get('host','')");   [ -n "$_v" ] && DB_HOST="$_v"
    _v=$(_rj "d.get('db',{}).get('port','')");   [ -n "$_v" ] && DB_PORT="$_v"
    _v=$(_rj "d.get('db',{}).get('name','')");   [ -n "$_v" ] && DB_NAME="$_v"
    _v=$(_rj "d.get('db',{}).get('user','')");   [ -n "$_v" ] && DB_USER="$_v"
    _v=$(_rj "d.get('db',{}).get('password','')"); [ -n "$_v" ] && DB_PASS="$_v"
fi

[ -z "$BACKUP_ROOT" ] && BACKUP_ROOT="$INSTALL_DIR/backups"

# ---------- 参数 ----------
while [ $# -gt 0 ]; do
    case "$1" in
        --retention)          RETENTION="$2"; shift 2 ;;
        --skip-attachments)   SKIP_ATTACHMENTS=true; shift ;;
        --skip-db)            SKIP_DB=true; shift ;;
        *)                    echo "未知参数: $1"; exit 1 ;;
    esac
done

TS=$(date '+%Y%m%d-%H%M%S')
DEST="$BACKUP_ROOT/backup-$TS"
mkdir -p "$DEST"

info() { echo "[INFO]  $*"; }
ok()   { echo "[OK]    $*"; }
warn() { echo "[WARN]  $*"; }

info "开始备份 -> $DEST"

# 1. 数据库
if [ "$SKIP_DB" = false ]; then
    if command -v mysqldump &>/dev/null; then
        SQL_FILE="$DEST/${DB_NAME}.sql"
        info "备份数据库..."
        mysqldump -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p"$DB_PASS" \
            --single-transaction --routines --triggers "$DB_NAME" > "$SQL_FILE" 2>/dev/null
        if [ $? -ne 0 ]; then
            warn "mysqldump 可能有问题，请检查备份文件完整性"
        else
            SIZE=$(du -h "$SQL_FILE" | cut -f1)
            ok "数据库备份完成: $SIZE"
        fi
    else
        warn "未找到 mysqldump，跳过数据库备份"
    fi
fi

# 2. 附件
if [ "$SKIP_ATTACHMENTS" = false ]; then
    ATTACH_DIR="$INSTALL_DIR/data/attachments"
    if [ -d "$ATTACH_DIR" ]; then
        TAR_FILE="$DEST/attachments.tar.gz"
        info "备份附件目录..."
        tar -czf "$TAR_FILE" -C "$(dirname "$ATTACH_DIR")" "$(basename "$ATTACH_DIR")"
        SIZE=$(du -h "$TAR_FILE" | cut -f1)
        ok "附件备份完成: $SIZE"
    else
        info "附件目录不存在: $ATTACH_DIR，跳过"
    fi
fi

# 3. 配置快照
CONFIG_FILE="$INSTALL_DIR/config/application-prod.yml"
if [ -f "$CONFIG_FILE" ]; then
    cp "$CONFIG_FILE" "$DEST/application-prod.yml"
    ok "配置快照完成"
fi

# 4. settings.json 快照
if [ -f "$SETTINGS_FILE" ]; then
    cp "$SETTINGS_FILE" "$DEST/settings.json"
fi

# 5. 清理旧备份
BACKUP_COUNT=$(find "$BACKUP_ROOT" -maxdepth 1 -type d -name "backup-*" | wc -l)
if [ "$BACKUP_COUNT" -gt "$RETENTION" ]; then
    REMOVE_COUNT=$((BACKUP_COUNT - RETENTION))
    find "$BACKUP_ROOT" -maxdepth 1 -type d -name "backup-*" | sort | head -n "$REMOVE_COUNT" | while read -r old; do
        info "清理旧备份: $(basename "$old")"
        rm -rf "$old"
    done
fi

ok "备份完成: $DEST"
