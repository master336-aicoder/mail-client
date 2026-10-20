#!/usr/bin/env bash
# ============================================================
# mail-client 安装脚本 (Kylin V10 SP3 x86_64)
#
# 流程：
#   预检(root/java21/mysql/redis/systemd) → 建运行用户 →
#   建目录/拷 jar → 渲染 application-prod.yml →
#   写 env 密钥文件 → 建库 → 装 systemd 单元 →
#   firewalld 放行 → SELinux/KYSEC 适配 → 启动 → 健康检查
#
# 用法：
#   sudo bash install.sh                # 首次生成 settings.json 后退出
#   sudo vi config/settings.json
#   sudo bash install.sh --confirm      # 正式安装
#   sudo bash install.sh --settings /path/to/settings.json --confirm
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/_common.sh"

SETTINGS=""
CONFIRM=false
RELEASE_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

while [ $# -gt 0 ]; do
    case "$1" in
        --settings) SETTINGS="$2"; shift 2 ;;
        --confirm|-c) CONFIRM=true; shift ;;
        *) die "未知参数: $1" ;;
    esac
done

[ -n "$SETTINGS" ] || SETTINGS="$SCRIPT_DIR/config/settings.json"
TPL="$SCRIPT_DIR/config/application-prod.template.yml"
[ -f "$TPL" ] || TPL="$RELEASE_ROOT/config/application-prod.template.yml"
UNIT_SRC="$SCRIPT_DIR/mail-client.service"
[ -f "$UNIT_SRC" ] || UNIT_SRC="$RELEASE_ROOT/systemd/mail-client.service"

# ---------- 首次运行：生成 settings.json ----------
if [ ! -f "$SETTINGS" ]; then
    EXAMPLE="$SCRIPT_DIR/config/settings.linux.example.json"
    [ -f "$EXAMPLE" ] || EXAMPLE="$RELEASE_ROOT/config/settings.linux.example.json"
    [ -f "$EXAMPLE" ] || die "找不到 settings 示例: $EXAMPLE"
    mkdir -p "$(dirname "$SETTINGS")"
    write_settings_json "$SETTINGS" "$EXAMPLE"
    ok "已生成 $SETTINGS（含随机 JWT_SECRET/MASTER_KEY，权限 600）"
    step "请核对：db.*/redis.*/installDir/javaHome/serverPort/publicOrigin/runUser，然后加 --confirm 重跑"
    exit 0
fi

if [ "$CONFIRM" != true ]; then
    warn "检测到 settings.json 但未加 --confirm，中止"
    info "关键项: installDir=$(read_setting "$SETTINGS" installDir) port=$(read_setting "$SETTINGS" serverPort) db=$(read_setting "$SETTINGS" db.host):$(read_setting "$SETTINGS" db.port)/$(read_setting "$SETTINGS" db.name)"
    step "确认后执行: sudo bash install.sh --confirm"
    exit 0
fi

require_root

INSTALL_DIR=$(read_setting "$SETTINGS" installDir)
SERVICE_NAME=$(read_setting "$SETTINGS" serviceName)
SERVER_PORT=$(read_setting "$SETTINGS" serverPort)
JAVA_HOME_SET=$(read_setting "$SETTINGS" javaHome)
RUN_USER=$(read_setting "$SETTINGS" runUser)
RUN_GROUP=$(read_setting "$SETTINGS" runGroup)
DB_HOST=$(read_setting "$SETTINGS" db.host)
DB_PORT=$(read_setting "$SETTINGS" db.port)
DB_NAME=$(read_setting "$SETTINGS" db.name)
DB_USER=$(read_setting "$SETTINGS" db.user)
DB_PW=$(read_setting "$SETTINGS" db.password)
DB_BIN=$(read_setting "$SETTINGS" db.mysqlBin)
REDIS_HOST=$(read_setting "$SETTINGS" redis.host)
REDIS_PORT=$(read_setting "$SETTINGS" redis.port)
REDIS_PW=$(read_setting "$SETTINGS" redis.password)
ALLOW_PRIVATE=$(read_setting "$SETTINGS" allowPrivateHost)
COOKIE_SECURE=$(read_setting "$SETTINGS" authCookieSecure)
PUBLIC_ORIGIN=$(read_setting "$SETTINGS" publicOrigin)
STORAGE_MODE=$(read_setting "$SETTINGS" storageMode)
JWT_SECRET=$(read_setting "$SETTINGS" jwtSecret)
MASTER_KEY=$(read_setting "$SETTINGS" masterKey)
JAVA_OPTS=$(read_setting "$SETTINGS" javaOpts)
FW_OPEN=$(read_setting "$SETTINGS" firewall.openPort)
FW_ZONE=$(read_setting "$SETTINGS" firewall.zone)
SELINUX_ADJ=$(read_setting "$SETTINGS" selinux.adjust)

[ -n "$RUN_USER" ] || RUN_USER=mail
[ -n "$RUN_GROUP" ] || RUN_GROUP=mail
[ -n "$FW_ZONE" ] || FW_ZONE=public
[ -n "$JAVA_OPTS" ] || JAVA_OPTS="-Xms512m -Xmx1024m -XX:+UseG1GC"
[ -n "$ALLOW_PRIVATE" ] || ALLOW_PRIVATE=false
[ -n "$COOKIE_SECURE" ] || COOKIE_SECURE=false
[ -n "$STORAGE_MODE" ] || STORAGE_MODE=local
[ -n "$FW_OPEN" ] || FW_OPEN=true
[ -n "$SELINUX_ADJ" ] || SELINUX_ADJ=true
# 布尔归一化为 true/false 字符串
case "$ALLOW_PRIVATE" in 1|true|True|TRUE) ALLOW_PRIVATE=true ;; *) ALLOW_PRIVATE=false ;; esac
case "$COOKIE_SECURE" in 1|true|True|TRUE) COOKIE_SECURE=true ;; *) COOKIE_SECURE=false ;; esac
case "$FW_OPEN"       in 1|true|True|TRUE) FW_OPEN=true ;; *) FW_OPEN=false ;; esac
case "$SELINUX_ADJ"   in 1|true|True|TRUE) SELINUX_ADJ=true ;; *) SELINUX_ADJ=false ;; esac

# ---------- 预检 ----------
step "预检运行环境..."
have systemctl || die "未检测到 systemd，Kylin V10 SP3 应自带；本脚本仅支持 systemd 模式"
JAVA_BIN=$(resolve_java "$JAVA_HOME_SET") || die "找不到 java，请安装 JDK 21 或修正 settings.javaHome"
check_java_21 "$JAVA_BIN" || die "Java 版本需 ≥21，当前 $("$JAVA_BIN" -version 2>&1 | head -1)"
ok "java=$JAVA_BIN ($("$JAVA_BIN" -version 2>&1 | head -1 | tr -d '\n'))"

JAR=$(find_release_jar "$RELEASE_ROOT/app")
[ -n "$JAR" ] || die "未在 $RELEASE_ROOT/app 找到 jar，请先执行 package.sh 或检查解压完整性"
JAR_NAME=$(basename "$JAR")
ok "jar=$JAR"

# ---------- 建运行用户 ----------
if ! id "$RUN_USER" >/dev/null 2>&1; then
    step "创建运行用户 $RUN_USER ..."
    groupadd -r "$RUN_GROUP" 2>/dev/null || true
    useradd -r -g "$RUN_GROUP" -s /sbin/nologin -d "$INSTALL_DIR" "$RUN_USER"
fi
ok "运行用户: $RUN_USER:$RUN_GROUP"

# ---------- 目录与产物 ----------
step "安装目录: $INSTALL_DIR"
for d in app config logs backups data/ost data/attachments data/exports; do
    mkdir -p "$INSTALL_DIR/$d"
done
cp "$JAR" "$INSTALL_DIR/app/$JAR_NAME"
# 若已有旧 jar 且名字不同，保留（升级路径由 upgrade.sh 负责）
ok "已就位 app/$JAR_NAME"

# ---------- 渲染 application-prod.yml ----------
step "渲染 config/application-prod.yml ..."
render_template "$TPL" "$INSTALL_DIR/config/application-prod.yml" \
    "__SERVER_PORT__=$SERVER_PORT" \
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
ok "$INSTALL_DIR/config/application-prod.yml"

# ---------- 密钥 env 文件（不写入 yml） ----------
step "写入密钥文件 config/env (0600) ..."
cat > "$INSTALL_DIR/config/env" <<EOF
# systemd EnvironmentFile: 密钥与额外 JVM 参数
JWT_SECRET=$JWT_SECRET
MASTER_KEY=$MASTER_KEY
JAVA_OPTS=$JAVA_OPTS
EOF
chmod 600 "$INSTALL_DIR/config/env"

# ---------- 属主 ----------
chown -R "$RUN_USER:$RUN_GROUP" "$INSTALL_DIR"
chmod 700 "$INSTALL_DIR/config"

# ---------- 建库 ----------
if [ -x "$DB_BIN" ]; then
    step "确保数据库存在: $DB_NAME ..."
    "$DB_BIN" -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -p"$DB_PW" \
        -e "CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;" \
        && ok "数据库已就绪（表结构由 Flyway 首次启动时自动创建）" \
        || die "建库失败，请核对 db.* 配置后重试"
else
    warn "未在 $DB_BIN 找到 mysql 客户端，跳过自动建库"
    warn "请 DBA 手动执行：CREATE DATABASE IF NOT EXISTS $DB_NAME CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
fi

# ---------- 安装 systemd 单元 ----------
[ -f "$UNIT_SRC" ] || die "找不到 systemd 单元源文件: $UNIT_SRC"
step "安装 /etc/systemd/system/${SERVICE_NAME}.service ..."
TMP_UNIT=$(mktemp)
sed -e "s|@SERVICE_NAME@|$SERVICE_NAME|g" \
    -e "s|@RUN_USER@|$RUN_USER|g" \
    -e "s|@RUN_GROUP@|$RUN_GROUP|g" \
    -e "s|@INSTALL_DIR@|$INSTALL_DIR|g" \
    -e "s|@JAR_NAME@|$JAR_NAME|g" \
    -e "s|@JAVA_BIN@|$JAVA_BIN|g" \
    -e "s|@SERVER_PORT@|$SERVER_PORT|g" \
    "$UNIT_SRC" > "$TMP_UNIT"
install -m 644 "$TMP_UNIT" "/etc/systemd/system/${SERVICE_NAME}.service"
rm -f "$TMP_UNIT"

if [ ! -d /var/log/mail-client ]; then
    mkdir -p /var/log/mail-client
    chown "$RUN_USER:$RUN_GROUP" /var/log/mail-client
fi

systemctl daemon-reload
systemctl enable "${SERVICE_NAME}.service" >/dev/null
ok "服务已注册并设开机自启"

# ---------- firewalld 放行 ----------
if [ "$FW_OPEN" = true ] && have firewall-cmd && firewall-cmd --state 2>/dev/null | grep -q running; then
    step "firewalld 放行端口 $SERVER_PORT (zone=$FW_ZONE) ..."
    firewall-cmd --permanent --zone="$FW_ZONE" --add-port="${SERVER_PORT}/tcp" >/dev/null
    firewall-cmd --reload >/dev/null
    ok "端口 $SERVER_PORT 已在 $FW_ZONE 区开放"
else
    info "跳过 firewalld 配置（未开启或不需要）"
fi

# ---------- SELinux / KYSEC 适配 ----------
if [ "$SELINUX_ADJ" = true ] && have getenforce && [ "$(getenforce)" = "Enforcing" ]; then
    step "SELinux 处于 Enforcing，调整应用目录上下文..."
    if have semanage; then
        semanage fcontext -a -t bin_t "$INSTALL_DIR/app(/.*)?" 2>/dev/null || true
        semanage fcontext -a -t var_log_t "/var/log/mail-client(/.*)?" 2>/dev/null || true
        restorecon -Rv "$INSTALL_DIR/app" /var/log/mail-client >/dev/null 2>&1 || true
        ok "SELinux fcontext 已更新"
    else
        warn "semanage 未安装（policycoreutils-python-utils），如需精细上下文请手动 yum install 后重跑"
    fi
fi
if [ "$SELINUX_ADJ" = true ] && have kysec-control; then
    warn "检测到麒麟 KYSEC，若 systemctl start 被拒绝请执行: sudo kysec-control disable 或改用状态 2 半禁用"
fi

# ---------- 启动 + 健康检查 ----------
step "启动服务..."
systemctl start "${SERVICE_NAME}.service"
sleep 3

if health_check "http://localhost:$SERVER_PORT" 120 3; then
    ok "安装完成"
    info "访问入口: ${PUBLIC_ORIGIN}/"
    info "健康检查: http://localhost:$SERVER_PORT/api/health"
    info "日志: $INSTALL_DIR/logs/stdout.log 或 journalctl -u $SERVICE_NAME -f"
    [ "$COOKIE_SECURE" = false ] && warn "当前 Cookie 未加 Secure；HTTPS 部署请修改 settings.authCookieSecure=true 后跑 upgrade.sh"
else
    err "服务已注册但健康检查失败，最后 50 行日志："
    journalctl -u "${SERVICE_NAME}.service" --no-pager -n 50 || true
    die "安装未完成：请修正配置后 `systemctl restart $SERVICE_NAME` 或运行 uninstall.sh 回退"
fi
