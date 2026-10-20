#!/usr/bin/env bash
# ============================================================
# mail-client 升级脚本 (Kylin V10 SP3 x86_64)
#
# 流程：
#   备份 (mysqldump + jar + yml + VERSION 快照) → 停服 →
#   换 jar / 重渲染 yml → 启服 (Flyway 自动迁移) →
#   健康检查；失败自动回滚 jar/配置并重启
#
# 用法：
#   sudo bash upgrade.sh --release /path/to/mail-client-1.0.1-linux-x86_64
#   sudo bash upgrade.sh --release ... --skip-backup --skip-health-check
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/_common.sh"

SETTINGS="$SCRIPT_DIR/config/settings.json"
RELEASE=""
SKIP_BACKUP=false
SKIP_HC=false

while [ $# -gt 0 ]; do
    case "$1" in
        --settings)          SETTINGS="$2"; shift 2 ;;
        --release)           RELEASE="$2"; shift 2 ;;
        --skip-backup)       SKIP_BACKUP=true; shift ;;
        --skip-health-check) SKIP_HC=true; shift ;;
        *) die "未知参数: $1" ;;
    esac
done

require_root
[ -n "$RELEASE" ] || die "必须指定 --release <新版本解包目录>"
[ -d "$RELEASE" ] || die "目录不存在: $RELEASE"
[ -f "$SETTINGS" ] || die "缺少 settings.json: $SETTINGS"

INSTALL_DIR=$(read_setting "$SETTINGS" installDir)
SVC=$(read_setting "$SETTINGS" serviceName)
PORT=$(read_setting "$SETTINGS" serverPort)
JAVA_BIN=$(read_setting "$SETTINGS" javaHome); JAVA_BIN="${JAVA_BIN}/bin/java"
[ -x "$JAVA_BIN" ] || JAVA_BIN=$(resolve_java "" | head -1)
DB_HOST=$(read_setting "$SETTINGS" db.host)
DB_PORT=$(read_setting "$SETTINGS" db.port)
DB_NAME=$(read_setting "$SETTINGS" db.name)
DB_USER=$(read_setting "$SETTINGS" db.user)
DB_PW=$(read_setting "$SETTINGS" db.password)
DB_BIN=$(read_setting "$SETTINGS" db.mysqlBin)
RUN_USER=$(read_setting "$SETTINGS" runUser)
RUN_GROUP=$(read_setting "$SETTINGS" runGroup)

NEW_JAR=$(find_release_jar "$RELEASE/app")
[ -n "$NEW_JAR" ] || die "新版本目录未找到 jar: $RELEASE/app"
NEW_NAME=$(basename "$NEW_JAR")

TPL="$RELEASE/config/application-prod.template.yml"
[ -f "$TPL" ] || TPL="$SCRIPT_DIR/config/application-prod.template.yml"
[ -f "$TPL" ] || die "找不到 application-prod.template.yml"

TS=$(date +%Y%m%d-%H%M%S)
BACKUP_DIR="$INSTALL_DIR/backups/$TS"
PROD_YML="$INSTALL_DIR/config/application-prod.yml"
APP_DIR="$INSTALL_DIR/app"
OLD_JAR=$(find_release_jar "$APP_DIR" || true)
BASE="http://localhost:$PORT"

step "升级目标: $NEW_NAME"
info "备份目录: $BACKUP_DIR"

# ---------- 1. 备份 ----------
mkdir -p "$BACKUP_DIR"
if [ "$SKIP_BACKUP" = false ]; then
    [ -n "$OLD_JAR" ] && cp "$OLD_JAR" "$BACKUP_DIR/" && info "已快照旧 jar: $(basename "$OLD_JAR")"
    [ -f "$PROD_YML" ] && cp "$PROD_YML" "$BACKUP_DIR/application-prod.yml" && info "已快照旧配置"
    [ -f "$INSTALL_DIR/config/env" ] && cp "$INSTALL_DIR/config/env" "$BACKUP_DIR/env"
    [ -f "$RELEASE/VERSION.json" ] && cp "$RELEASE/VERSION.json" "$BACKUP_DIR/new-VERSION.json"
    [ -f "$INSTALL_DIR/VERSION.installed.json" ] && cp "$INSTALL_DIR/VERSION.installed.json" "$BACKUP_DIR/"
    if [ -x "$DB_BIN" ]; then
        DUMP_BIN=$(dirname "$DB_BIN")/mysqldump
        if [ -x "$DUMP_BIN" ]; then
            step "mysqldump 备份数据库 $DB_NAME ..."
            MYSQL_PWD="$DB_PW" "$DUMP_BIN" \
                -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" \
                --single-transaction --routines --triggers \
                "$DB_NAME" > "$BACKUP_DIR/${DB_NAME}-${TS}.sql"
            ok "数据库备份: $BACKUP_DIR/${DB_NAME}-${TS}.sql"
        else
            warn "未找到 mysqldump ($DUMP_BIN)，跳过 DB 备份"
        fi
    else
        warn "settings.db.mysqlBin 不可执行，跳过 DB 备份"
    fi
else
    warn "按 --skip-backup 跳过备份"
fi

# ---------- 2. 停服 + 换件 ----------
step "停止服务 $SVC ..."
systemctl stop "$SVC"
sleep 2

if [ -n "$OLD_JAR" ]; then
    mv "$OLD_JAR" "$BACKUP_DIR/$(basename "$OLD_JAR").replaced"
fi
install -m 644 -o "$RUN_USER" -g "$RUN_GROUP" "$NEW_JAR" "$APP_DIR/$NEW_NAME"

# 重渲染 yml
ALLOW_PRIVATE=$(read_setting "$SETTINGS" allowPrivateHost)
COOKIE_SECURE=$(read_setting "$SETTINGS" authCookieSecure)
PUBLIC_ORIGIN=$(read_setting "$SETTINGS" publicOrigin)
STORAGE_MODE=$(read_setting "$SETTINGS" storageMode)
REDIS_HOST=$(read_setting "$SETTINGS" redis.host)
REDIS_PORT=$(read_setting "$SETTINGS" redis.port)
REDIS_PW=$(read_setting "$SETTINGS" redis.password)
case "$ALLOW_PRIVATE"  in 1|true|True|TRUE) ALLOW_PRIVATE=true ;; *) ALLOW_PRIVATE=false ;; esac
case "$COOKIE_SECURE"  in 1|true|True|TRUE) COOKIE_SECURE=true ;; *) COOKIE_SECURE=false ;; esac

step "重渲染 application-prod.yml ..."
render_template "$TPL" "$PROD_YML" \
    "__SERVER_PORT__=$PORT" \
    "__DB_HOST__=$DB_HOST" \
    "__DB_PORT__=$DB_PORT" \
    "__DB_NAME__=$DB_NAME" \
    "__DB_USER__=$DB_USER" \
    "__DB_PASSWORD__=$DB_PW" \
    "__REDIS_HOST__=$REDIS_HOST" \
    "__REDIS_PORT__=$REDIS_PORT" \
    "__REDIS_PASSWORD__=$REDIS_PW" \
    "__ALLOW_PRIVATE_HOST__=$ALLOW_PRIVATE" \
    "__AUTH_COOKIE_SECURE__=$COOKIE_SECURE" \
    "__PUBLIC_ORIGIN__=$PUBLIC_ORIGIN" \
    "__STORAGE_MODE__=$STORAGE_MODE"
chown "$RUN_USER:$RUN_GROUP" "$PROD_YML"
chmod 600 "$PROD_YML"

# 更新 systemd 单元的 jar 名（若版本号变了）
UNIT="/etc/systemd/system/${SVC}.service"
if [ -f "$UNIT" ]; then
    step "更新 systemd 单元中的 jar 路径..."
    sed -i -E "s|app/mail-client-backend-[^/]+\.jar|app/$NEW_NAME|g" "$UNIT"
    systemctl daemon-reload
fi

# 记录已安装版本
[ -f "$RELEASE/VERSION.json" ] && cp "$RELEASE/VERSION.json" "$INSTALL_DIR/VERSION.installed.json"

# ---------- 3. 启服 (Flyway 自动迁移) ----------
step "启动服务（Flyway 自动迁移）..."
systemctl start "$SVC"

if [ "$SKIP_HC" = true ]; then
    warn "按 --skip-health-check 跳过健康检查，请自行访问 $BASE/api/health"
    exit 0
fi

if health_check "$BASE" 120 3; then
    ok "升级成功，健康检查通过"
    info "回滚快照保留在: $BACKUP_DIR"
    exit 0
fi

# ---------- 4. 回滚 ----------
err "健康检查失败，开始自动回滚..."
systemctl stop "$SVC" 2>/dev/null || true
rm -f "$APP_DIR/$NEW_NAME"
if [ -n "$OLD_JAR" ] && [ -f "$BACKUP_DIR/$(basename "$OLD_JAR").replaced" ]; then
    install -m 644 -o "$RUN_USER" -g "$RUN_GROUP" \
        "$BACKUP_DIR/$(basename "$OLD_JAR").replaced" "$OLD_JAR"
fi
[ -f "$BACKUP_DIR/application-prod.yml" ] && cp "$BACKUP_DIR/application-prod.yml" "$PROD_YML"
[ -f "$BACKUP_DIR/env" ] && cp "$BACKUP_DIR/env" "$INSTALL_DIR/config/env"
if [ -f "$UNIT" ] && [ -n "$OLD_JAR" ]; then
    sed -i -E "s|app/mail-client-backend-[^/]+\.jar|app/$(basename "$OLD_JAR")|g" "$UNIT"
    systemctl daemon-reload
fi
step "用旧版本重启..."
systemctl start "$SVC"
if health_check "$BASE" 60 3; then
    ok "回滚后旧版本健康检查通过"
else
    err "回滚后仍不健康，请人工排查：journalctl -u $SVC -n 200"
fi
if [ -f "$BACKUP_DIR/${DB_NAME}-${TS}.sql" ]; then
    warn "数据库如需回滚，请手动恢复："
    warn "  mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p $DB_NAME < $BACKUP_DIR/${DB_NAME}-${TS}.sql"
fi
die "升级失败，已回滚到旧版本。"
