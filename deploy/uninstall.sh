#!/usr/bin/env bash
# ============================================================
# mail-client 卸载脚本 (Kylin V10 SP3 x86_64)
#
# 默认停止并移除 systemd 服务、firewalld 规则；保留安装目录与数据库。
# 传 --purge-data 才 rm -rf 安装目录。数据库永不自动 drop。
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/_common.sh"

SETTINGS="$SCRIPT_DIR/config/settings.json"
PURGE=false
while [ $# -gt 0 ]; do
    case "$1" in
        --settings)    SETTINGS="$2"; shift 2 ;;
        --purge-data)  PURGE=true; shift ;;
        *) die "未知参数: $1" ;;
    esac
done

require_root

SVC="mail-client"
INSTALL_DIR=""
FW_ZONE="public"
SERVER_PORT=8080
if [ -f "$SETTINGS" ]; then
    SVC=$(read_setting "$SETTINGS" serviceName); [ -n "$SVC" ] || SVC=mail-client
    INSTALL_DIR=$(read_setting "$SETTINGS" installDir)
    SERVER_PORT=$(read_setting "$SETTINGS" serverPort); [ -n "$SERVER_PORT" ] || SERVER_PORT=8080
    FW_ZONE=$(read_setting "$SETTINGS" firewall.zone); [ -n "$FW_ZONE" ] || FW_ZONE=public
else
    warn "未找到 settings.json，使用默认服务名 mail-client"
fi

if service_exists "$SVC"; then
    step "停止并移除 systemd 服务 $SVC ..."
    systemctl stop "$SVC" 2>/dev/null || true
    systemctl disable "$SVC" 2>/dev/null || true
    rm -f "/etc/systemd/system/${SVC}.service"
    systemctl daemon-reload
    systemctl reset-failed 2>/dev/null || true
    ok "服务已移除"
else
    info "服务 $SVC 不存在，跳过"
fi

if have firewall-cmd && firewall-cmd --state 2>/dev/null | grep -q running; then
    if firewall-cmd --permanent --zone="$FW_ZONE" --query-port="${SERVER_PORT}/tcp" 2>/dev/null; then
        step "撤回 firewalld 端口 ${SERVER_PORT}/tcp ..."
        firewall-cmd --permanent --zone="$FW_ZONE" --remove-port="${SERVER_PORT}/tcp" >/dev/null
        firewall-cmd --reload >/dev/null
    fi
fi

if [ "$PURGE" = true ] && [ -n "$INSTALL_DIR" ] && [ -d "$INSTALL_DIR" ]; then
    warn "--purge-data：删除安装目录 $INSTALL_DIR"
    rm -rf "$INSTALL_DIR"
else
    [ -n "$INSTALL_DIR" ] && info "保留安装目录: $INSTALL_DIR（含数据与备份）"
fi

info "注意：数据库 mail_client 未被删除。如需清理，请 DBA 手动 DROP DATABASE。"
