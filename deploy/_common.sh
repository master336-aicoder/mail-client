#!/usr/bin/env bash
# _common.sh — mail-client Linux 部署共享函数（Kylin V10 SP3 / RHEL 8 系）
# 被 package.sh / install.sh / upgrade.sh / uninstall.sh 通过 `source` 引入。
# 只放工具函数，不执行业务动作。

set -euo pipefail

_TS() { date '+%Y-%m-%d %H:%M:%S'; }
info() { echo "[$(_TS)] [INFO] $*"; }
step() { echo "[$(_TS)] [STEP] $*"; }
ok()   { echo "[$(_TS)] [OK]   $*"; }
warn() { echo "[$(_TS)] [WARN] $*" >&2; }
err()  { echo "[$(_TS)] [ERROR] $*" >&2; }
die()  { err "$*"; exit 1; }

# 是否为 root（Kylin 上 systemd/firewalld 需要）
require_root() {
    if [ "$(id -u)" != "0" ]; then
        die "此脚本需 root 权限（systemd/firewalld/chown），请用 sudo 运行。"
    fi
}

# 生成随机十六进制密钥，$1=字节数
random_hex() {
    local bytes=${1:-32}
    od -An -tx1 -N "$bytes" /dev/urandom | tr -d ' \n'
}

# 检测命令存在
have() { command -v "$1" >/dev/null 2>&1; }

# 读取 settings.json 字段（支持点路径 db.host）。使用 python3（Kylin 自带）。
read_setting() {
    local file=$1 path=$2
    python3 -c "
import json,sys
d=json.load(open('$file'))
for k in '$path'.split('.'):
    if isinstance(d, dict) and k in d: d=d[k]
    else: sys.exit(0)
print(d if d is not None else '')
" 2>/dev/null || true
}

# 从 settings.json 写回一个字段（首次安装时注入随机密钥）
write_settings_json() {
    local file=$1 src=$2
    python3 -c "
import json
s=json.load(open('$src'))
s.setdefault('jwtSecret','')
s.setdefault('masterKey','')
if not s['jwtSecret']: s['jwtSecret']='$(random_hex 32)'
if not s['masterKey']: s['masterKey']='$(random_hex 32)'
json.dump(s, open('$file','w'), ensure_ascii=False, indent=2)
"
    chmod 600 "$file"
}

# 渲染 application-prod.template.yml → application-prod.yml
# 通过 --kv 传入 KEY=VALUE 列表（KEY 含前后双下划线）
render_template() {
    local tpl=$1 out=$2; shift 2
    [ -f "$tpl" ] || die "模板不存在: $tpl"
    python3 - "$tpl" "$out" "$@" <<'PY'
import sys
tpl, out = sys.argv[1], sys.argv[2]
pairs = sys.argv[3:]
data = open(tpl, encoding='utf-8').read()
for kv in pairs:
    if '=' not in kv: continue
    k, v = kv.split('=', 1)
    data = data.replace(k, v)
open(out, 'w', encoding='utf-8', newline='\n').write(data)
PY
    chmod 600 "$out"
}

# 轮询 /api/health 直至 status=UP 或超时
health_check() {
    local base=${1:-http://localhost:8080} timeout=${2:-120} interval=${3:-3}
    local url="$base/api/health"
    local waited=0
    info "健康检查 $url （最长 ${timeout}s）"
    while [ $waited -lt "$timeout" ]; do
        if have curl; then
            local body
            body=$(curl -sf --max-time 5 "$url" 2>/dev/null || true)
            if echo "$body" | grep -q '"status":"UP"' && echo "$body" | grep -q '"db":"UP"'; then
                ok "健康检查通过：$body"
                return 0
            fi
            [ -n "$body" ] && info "等待依赖就绪: $body"
        fi
        sleep "$interval"
        waited=$((waited + interval))
    done
    err "健康检查超时（${timeout}s）"
    return 1
}

# 解析 java 可执行：优先 $javaHome，其次 $JAVA_HOME，最后 PATH
resolve_java() {
    local preferred=${1:-}
    for c in "$preferred/bin/java" "$JAVA_HOME/bin/java" "$(command -v java 2>/dev/null || true)"; do
        if [ -n "$c" ] && [ -x "$c" ]; then echo "$c"; return; fi
    done
    return 1
}

# 校验 Java >= 21；返回空则通过，否则给出原因
check_java_21() {
    local javabin=$1
    [ -x "$javabin" ] || return 1
    local v
    v=$("$javabin" -version 2>&1 | awk -F'"' 'NR==1{print $2}')
    local major=${v%%.*}
    [ -n "$major" ] && [ "$major" -ge 21 ] 2>/dev/null || return 2
}

# 找发布包里的 jar（排除 sources/original）
find_release_jar() {
    local appdir=$1
    ls "$appdir"/mail-client-backend-*.jar 2>/dev/null \
        | grep -vE 'sources\.jar$|original\.jar$' | head -1
}

# 服务名一致性检查
service_exists() {
    local name=$1
    systemctl list-unit-files "${name}.service" >/dev/null 2>&1 \
        && [ -n "$(systemctl list-unit-files "${name}.service" --no-legend 2>/dev/null || true)" ]
}
