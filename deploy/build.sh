#!/usr/bin/env bash
# ============================================================
#  mail-client 编译打包脚本 (Linux)
#  用法: build.sh [--frontend-only] [--backend-only] [--skip-tests] [--no-skip-tests]
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BACKEND_DIR="$REPO_ROOT/backend"
FRONTEND_DIR="$REPO_ROOT/frontend"
STATIC_DIR="$BACKEND_DIR/src/main/resources/static"

SKIP_TESTS=true
MODE="full"

# ---------- 解析参数 ----------
while [ $# -gt 0 ]; do
    case "$1" in
        --frontend-only)   MODE="frontend"; shift ;;
        --backend-only)    MODE="backend"; shift ;;
        --skip-tests)      SKIP_TESTS=true; shift ;;
        --no-skip-tests)   SKIP_TESTS=false; shift ;;
        *)                 echo "未知参数: $1"; exit 1 ;;
    esac
done

info() { echo "[STEP] $*"; }
ok()   { echo "[OK]   $*"; }

# ---------- Java 版本 ----------
info "Java 版本:"
java -version 2>&1
echo ""

# ---------- 前端构建 ----------
if [ "$MODE" != "backend" ]; then
    info "构建前端..."
    cd "$FRONTEND_DIR"
    npm ci
    npm run build
    ok "前端构建完成"
    if [ "$MODE" = "frontend" ]; then
        exit 0
    fi
fi

# ---------- 拷贝 dist → static ----------
if [ "$MODE" != "frontend" ] || [ "$MODE" = "full" ]; then
    info "拷贝前端产物到 backend static..."
    rm -rf "$STATIC_DIR"
    mkdir -p "$STATIC_DIR"
    if [ -d "$FRONTEND_DIR/dist" ]; then
        cp -r "$FRONTEND_DIR/dist/"* "$STATIC_DIR/"
    fi
    ok "拷贝完成"
fi

if [ "$MODE" = "frontend" ]; then
    exit 0
fi

# ---------- Maven 打包 ----------
info "Maven 打包后端..."
cd "$BACKEND_DIR"
MVN_ARGS="-B clean package"
if [ "$SKIP_TESTS" = "true" ]; then
    MVN_ARGS="$MVN_ARGS -DskipTests"
fi
mvn $MVN_ARGS

echo ""
ok "打包完成"
echo "  jar: $BACKEND_DIR/target/mail-client-backend-1.0.0.jar"
echo ""
echo "[INFO] 启动命令:"
echo "  bash $SCRIPT_DIR/start-service.sh --dev"
echo "  或 java -jar $BACKEND_DIR/target/mail-client-backend-1.0.0.jar --spring.profiles.active=dev"
