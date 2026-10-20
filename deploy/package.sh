#!/usr/bin/env bash
# ============================================================
# mail-client 打包脚本 (Linux / Kylin V10 SP3 x86_64)
#
# 步骤：
#   1) 构建前端 (npm ci + npm run build) → frontend/dist
#   2) 拷贝 dist 到 backend/src/main/resources/static（单进程托管）
#   3) Maven 打包后端 → backend/target/mail-client-backend-<ver>.jar
#   4) 组装发布目录：app/ scripts/ config/ systemd/ docs/ VERSION.json
#   5) 全部 sh 强制 LF + chmod +x
#   6) 压缩为 tar.gz，附 SHA256
#
# 用法：
#   bash deploy/package.sh --version 1.0.0
#   bash deploy/package.sh --version 1.0.0 --skip-frontend
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/_common.sh"

VERSION="1.0.0"
SKIP_FRONTEND=false
SKIP_TESTS=true
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUT_DIR="$REPO_ROOT/dist-release"
TARGET_ARCH="linux-x86_64"

while [ $# -gt 0 ]; do
    case "$1" in
        --version)         VERSION="$2"; shift 2 ;;
        --skip-frontend)   SKIP_FRONTEND=true; shift ;;
        --run-tests)       SKIP_TESTS=false; shift ;;
        --out-dir)         OUT_DIR="$2"; shift 2 ;;
        --repo-root)       REPO_ROOT="$2"; shift 2 ;;
        *)                 die "未知参数: $1" ;;
    esac
done

BACKEND_DIR="$REPO_ROOT/backend"
FRONTEND_DIR="$REPO_ROOT/frontend"
STATIC_DIR="$BACKEND_DIR/src/main/resources/static"
TARGET_DIR="$BACKEND_DIR/target"
RELEASE_NAME="mail-client-${VERSION}-${TARGET_ARCH}"
RELEASE_DIR="$OUT_DIR/$RELEASE_NAME"

step "开始打包 mail-client v$VERSION 目标=$TARGET_ARCH"
info "仓库根: $REPO_ROOT"

# ---------- 预检 ----------
have npm  || die "找不到 npm，请安装 Node 18+"
have mvn  || die "找不到 mvn，请安装 Maven 3.8+"
JAVA_BIN=$(resolve_java "${JAVA_HOME:-}") || die "找不到 java，请安装 JDK 21"
check_java_21 "$JAVA_BIN" || die "Java 版本需 ≥21，当前 $($JAVA_BIN -version 2>&1 | head -1)"
ok "构建工具就绪 java=$JAVA_BIN npm=$(npm -v) mvn=$(mvn -v | head -1)"

# ---------- 1. 前端 ----------
if [ "$SKIP_FRONTEND" = false ]; then
    step "构建前端 (npm ci + npm run build)..."
    pushd "$FRONTEND_DIR" >/dev/null
    npm ci --no-audit --no-fund
    npm run build
    popd >/dev/null
else
    warn "跳过前端构建，复用 frontend/dist"
fi

[ -f "$FRONTEND_DIR/dist/index.html" ] || die "未找到 $FRONTEND_DIR/dist/index.html，请先不带 --skip-frontend 运行"

# ---------- 2. dist → static ----------
step "拷贝前端产物到 backend classpath:/static..."
rm -rf "$STATIC_DIR"
mkdir -p "$STATIC_DIR"
cp -R "$FRONTEND_DIR/dist/." "$STATIC_DIR/"

# ---------- 3. Maven ----------
step "Maven 打包后端..."
pushd "$BACKEND_DIR" >/dev/null
MVN_ARGS=(-B clean package)
[ "$SKIP_TESTS" = true ] && MVN_ARGS+=(-DskipTests)
mvn "${MVN_ARGS[@]}"
popd >/dev/null

JAR_NAME="mail-client-backend-${VERSION}.jar"
JAR_PATH="$TARGET_DIR/$JAR_NAME"
[ -f "$JAR_PATH" ] || JAR_PATH=$(find_release_jar "$TARGET_DIR")
[ -n "${JAR_PATH:-}" ] && [ -f "$JAR_PATH" ] || die "未在后端 target 目录找到可执行 jar"
JAR_NAME=$(basename "$JAR_PATH")
ok "后端 jar: $JAR_PATH"

# ---------- 4. 组装发布目录 ----------
step "组装发布目录: $RELEASE_DIR"
mkdir -p "$OUT_DIR"
[ -d "$RELEASE_DIR" ] && rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR/app" "$RELEASE_DIR/scripts" "$RELEASE_DIR/config" "$RELEASE_DIR/systemd" "$RELEASE_DIR/docs"

cp "$JAR_PATH" "$RELEASE_DIR/app/$JAR_NAME"

# 拷贝所有 .sh（LF + 可执行），systemd 单元单独放
for f in "$SCRIPT_DIR"/*.sh; do
    base=$(basename "$f")
    cp "$f" "$RELEASE_DIR/scripts/$base"
    # 强制 LF（防止 CRLF 在 Kylin 上导致 bad interpreter）
    sed -i 's/\r$//' "$RELEASE_DIR/scripts/$base"
    chmod +x "$RELEASE_DIR/scripts/$base"
done

cp "$SCRIPT_DIR/mail-client.service" "$RELEASE_DIR/systemd/mail-client.service"
sed -i 's/\r$//' "$RELEASE_DIR/systemd/mail-client.service"

cp "$SCRIPT_DIR/config/application-prod.template.yml" "$RELEASE_DIR/config/"
cp "$SCRIPT_DIR/config/settings.linux.example.json" "$RELEASE_DIR/config/"
sed -i 's/\r$//' "$RELEASE_DIR/config/"*

[ -f "$REPO_ROOT/DEPLOY.md" ] && cp "$REPO_ROOT/DEPLOY.md" "$RELEASE_DIR/docs/"
cp "$REPO_ROOT/doc/运维文档.md" "$RELEASE_DIR/docs/运维文档.md" 2>/dev/null || true

# README for the release dir itself
cat > "$RELEASE_DIR/README.txt" <<EOF
mail-client v$VERSION — Kylin V10 SP3 x86_64 部署包
================================================

目录结构:
  app/          可执行 jar（已内嵌前端 SPA）
  scripts/      部署/服务管理脚本（bash，LF 换行，已 chmod +x）
  config/       settings 示例 + application-prod.yml 模板
  systemd/      mail-client.service 单元文件
  docs/         运维文档

快速安装：
  sudo bash scripts/install.sh           # 首次生成 settings.json 并退出
  sudo vi config/settings.json           # 核对数据库/Redis/端口
  sudo bash scripts/install.sh --confirm # 正式安装 + systemd 注册 + 启动

后续运维：
  sudo systemctl status mail-client
  bash scripts/service.sh restart
  bash scripts/backup.sh

回滚 / 升级 / 卸载：
  bash scripts/upgrade.sh --release /path/to/new-release
  bash scripts/uninstall.sh

详见 docs/运维文档.md 第 17 章。
EOF

# VERSION.json
GIT_SHA=$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo "")
JAR_SHA=$(sha256sum "$JAR_PATH" | awk '{print $1}')
BUILD_TIME=$(date '+%Y-%m-%d %H:%M:%S')
cat > "$RELEASE_DIR/VERSION.json" <<EOF
{
  "name": "mail-client",
  "version": "$VERSION",
  "buildTime": "$BUILD_TIME",
  "gitCommit": "$GIT_SHA",
  "targetArch": "$TARGET_ARCH",
  "targetOs": "Kylin V10 SP3 (Linux x86_64)",
  "jar": "app/$JAR_NAME",
  "jarSha256": "$JAR_SHA",
  "frontend": "bundled-in-jar (classpath:/static)",
  "requires": {
    "java": ">=21",
    "mysql": ">=8.0",
    "redis": ">=3.2"
  }
}
EOF

# ---------- 5. 压缩 ----------
step "压缩发布包..."
TARBALL="$OUT_DIR/${RELEASE_NAME}.tar.gz"
popd_dir=$(pwd)
cd "$OUT_DIR"
tar -czf "$TARBALL" "$RELEASE_NAME"
cd "$popd_dir"

SHA=$(sha256sum "$TARBALL" | awk '{print $1}')
ls -lh "$TARBALL"

ok "打包完成"
info "  目录:   $RELEASE_DIR"
info "  压缩包: $TARBALL"
info "  SHA256: $SHA"
step "上传 tar.gz 到 Kylin 目标机 → sha256sum -c → 解压 → sudo bash scripts/install.sh"
