# 云邮 Mail — Web 邮箱客户端

多用户 Web 邮箱客户端。每个用户拥有独立的服务端工作存储（OST），邮件内容可持久化为
PST 供 Outlook 读取。兼容主流收/发协议（IMAP4 / POP3 / SMTP）与加密方式（SSL / STARTTLS / NONE），
支持文件夹、筛选规则、实时推送与 PWA 离线访问。

> 技术栈：**Spring Boot 3.3 + JDK 21** 后端 · **Vue 3 + TypeScript + Vite + Element Plus + PWA** 前端

---

## 目录结构

```
mail-client/
├── doc/                     # 项目文档
│   ├── 需求分析文档.md        # 需求分析
│   ├── 概要设计文档.md        # 系统概要设计
│   ├── 详细设计文档.md        # 模块详细设计
│   ├── UI设计规范.md         # 通用 UI 设计规范（设计令牌 / 通用组件）
│   ├── UI设计文档.md         # 业务页面 UI 设计
│   ├── 开发计划.md           # 开发排期
│   ├── 测试用例文档.md       # 功能按钮级 + 后端接口级测试用例（≈158 条）
│   ├── 运维文档.md           # 部署运维、巡检备份、故障排查
│   ├── 二次开发手册.md       # 架构说明、模块指引、扩展指南
│   └── 基础环境清单.txt      # 环境依赖清单
├── design/mockup.html       # 高保真交互原型
├── deploy/                  # 部署与运维脚本（Windows .bat/.ps1 + Linux .sh）
│   ├── build.ps1 / build.bat / build.sh       # 编译打包
│   ├── start-service.bat / start-service.sh   # 直接启动（无需 NSSM/systemd）
│   ├── service.ps1 / service.bat / service.sh # 服务启停管理
│   ├── inspect.ps1 / inspect.sh               # 系统巡检
│   ├── backup.ps1 / backup.sh                 # 数据备份
│   ├── logs.ps1 / logs.sh                     # 日志查看
│   ├── mail-client.service                    # Linux systemd 单元模板
│   ├── package.ps1          # 制品打包（含前端 + 后端 + 版本清单）
│   ├── install.ps1          # 首次安装（Windows，生成密钥 + 注册 NSSM 服务）
│   ├── upgrade.ps1          # 版本升级（Windows，备份 + 换件 + 自动回滚）
│   ├── uninstall.ps1        # 卸载（Windows）
│   └── config/              # 配置模板
├── backend/                 # Spring Boot 3 后端
│   ├── pom.xml
│   └── src/main/java/com/mail/...
├── frontend/                # Vue 3 + Vite 前端
│   └── src/...
├── DEPLOY.md                # 部署指南（Windows 服务器）
├── README.md                # 本文档
├── SECURITY_PENTEST_REPORT.md  # 渗透测试报告
└── TEST_REPORT.md           # 功能测试报告
```

---

## 文档索引

| 文档 | 说明 | 适用角色 |
|------|------|---------|
| [README.md](README.md) | 项目概述、快速启动、功能清单 | 所有人 |
| [DEPLOY.md](DEPLOY.md) | Windows 服务器部署指南（安装/升级/回滚/HTTPS） | 运维 |
| [doc/运维文档.md](doc/运维文档.md) | 日常运维：巡检、备份、日志、故障排查、安全加固 | 运维 |
| [doc/二次开发手册.md](doc/二次开发手册.md) | 架构说明、模块指引、API 规范、扩展指南 | 开发者 |
| [doc/需求分析文档.md](doc/需求分析文档.md) | 功能需求与非功能需求 | 产品/开发 |
| [doc/概要设计文档.md](doc/概要设计文档.md) | 系统架构与模块划分 | 开发 |
| [doc/详细设计文档.md](doc/详细设计文档.md) | 各模块详细设计 | 开发 |
| [doc/UI设计规范.md](doc/UI设计规范.md) | 设计令牌、通用组件规范 | 前端/设计 |
| [doc/UI设计文档.md](doc/UI设计文档.md) | 业务页面 UI 设计 | 前端/设计 |
| [doc/测试用例文档.md](doc/测试用例文档.md) | 功能按钮级 + API 级测试用例 | 测试/开发 |
| [SECURITY_PENTEST_REPORT.md](SECURITY_PENTEST_REPORT.md) | 渗透测试报告与修复记录 | 安全/开发 |
| [TEST_REPORT.md](TEST_REPORT.md) | 全量功能测试报告 | 测试/开发 |

---

## 已落地功能

| 模块 | 说明 | 前后端 |
|------|------|--------|
| 认证授权 | JWT(HS256) + HttpOnly Cookie + CSRF 双重提交 + BCrypt + 登录限流 | ✅ |
| 邮箱账户 | 自动探测服务器、多协议绑定、凭据 AES-256-GCM 加密存储、收发分离加密 | ✅ |
| 收信协议 | IMAP4 / POP3 拉取、SMTP 发送（SSL/STARTTLS）、证书例外支持 | ✅ |
| 文件夹 | 树形结构、默认收件箱/草稿/已发送/已删除、未读数 | ✅ |
| 邮件 | 列表分页、详情、标记(已读/星标/重要)、删除、草稿、批量操作、批量移动 | ✅ |
| 写信 | 富文本编辑（wangEditor）、内联图片附件、转发 | ✅ |
| 附件 | 抽象存储接口：本地文件 与 MinIO 可切换 | ✅ |
| 筛选规则 | 条件(发件人/主题/正文/含附件) → 动作(星标/已读/删除/移动)，按优先级 | ✅ |
| 实时推送 | WebSocket(`/api/ws`) MAIL_NEW 等事件推送，断线重连 | ✅ |
| 定时同步 | 可配置周期（间隔/定点）轮询拉取新邮件 → 入库 → 规则 → 推送 | ✅ |
| 后台任务 | 任务定义/执行记录/产物三层模型，批量操作，保存策略 | ✅ |
| PST/OST | java-libpst 生成/读取 PST，导出供 Outlook | ✅ |
| 通知中心 | 系统通知 + 管理员公告 | ✅ |
| 管理后台 | 用户管理、角色管理、公告发布、服务状态监控 | ✅ |
| 前端页面 | 登录/注册、邮件三栏、账户向导、规则管理、任务管理、消息中心、PST 导出、通用设置、后台管理 | ✅ |
| PWA | vite-plugin-pwa 离线缓存、可安装 | ✅ |

---

## 环境前置（见 `doc/基础环境清单.txt`）

| 组件 | 版本 | 地址 |
|------|------|------|
| JDK | 21 | `D:\Java\jdk-21` |
| Maven | 3.6.3 | `D:\apache-maven-3.6.3` |
| MySQL | 8 | `localhost:3306`，`root/root` |
| Redis | 3.2 | `localhost:6379`，密码 `redis` |
| MinIO | 最新 | `localhost:9000`，`admin/admin123456`（可选，仅 storage-mode=minio） |
| Node | 22 | `node -v` |
| Nacos | 2.4.3 | 可选，未使用（本地配置直接进 application.yml） |

---

## 启动步骤

### 1. 初始化数据库

```sql
CREATE DATABASE IF NOT EXISTS mail_client DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

Flyway 会在应用启动时自动执行 `db/migration/V1__init.sql`。

### 2. 启动后端

```bash
cd backend
# 设置 JDK 21
export JAVA_HOME="D:\Java\jdk-21"
mvn -version

# 方式一：Maven 直接运行（开发推荐）
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 方式二：先打包再运行
mvn clean package -DskipTests
java -jar target/mail-client-backend-1.0.0.jar --spring.profiles.active=dev
```

> 端口 `8080`，健康检查 `GET http://localhost:8080/api/health`。
> 自定义密钥通过环境变量覆盖：
> ```bash
> export JWT_SECRET="your-256bit-secret..."
> export MASTER_KEY="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
> ```

### 3. 启动前端

```bash
cd frontend
npm install
npm run dev
```

> 访问 `http://localhost:5173`。开发代理将 `/api` 转发到 `:8080`。
> 生产构建：`npm run build && npm run preview`。

### 快捷方式（使用脚本）

**Windows:**
```powershell
# 一键编译打包
powershell -ExecutionPolicy Bypass -File deploy\build.ps1

# 以 dev profile 直接运行后端
deploy\start-service.bat --dev
```

**Linux:**
```bash
# 一键编译打包
bash deploy/build.sh

# 以 dev profile 直接运行后端
bash deploy/start-service.sh --dev

# 后台运行
bash deploy/start-service.sh --dev --background
```

### 4. 使用

- 注册账号 → 登录。
- 「账户管理」绑定一个邮箱账户（可用自动探测，或手动填 IMAP/POP3/SMTP 与授权码）。
- 账户即触发首次同步；之后每 60s 轮询新邮件并实时推送。
- 收件箱阅读 → 星标/删除/回复 → 规则自动归类 → PST 导出供 Outlook 读取。

---

## 附件存储切换（双存储）

默认 `mail.app.storage-mode=local`（存 `./data/attachments`）。

切换到 MinIO（需先启动 MinIO）：

```yaml
mail:
  app:
    storage-mode: minio
  storage:
    minio:
      endpoint: http://localhost:9000
      access-key: admin
      secret-key: admin123456
      bucket: mail-attachments
```

实现：`AttachmentStorage` 接口 →
`LocalFileStorage`（本地）、`MinioStorage`（MinIO，按 `storage-mode=minio` 条件装配），
`StorageRouter` 按配置路由，元数据记录 `storage_type`。

---

## 安全说明

- 密码 BCrypt；JWT HS256 + HttpOnly Cookie 状态无关认证。
- CSRF 双重提交：`csrf_token` Cookie + `X-CSRF-Token` 请求头。
- 账户凭据使用 AES-256-GCM 加密落库，`MASTER_KEY` 生产须通过环境变量注入。
- SSRF 防护：`allow-private-host=false` 时拦截内网/回环邮件主机。
- XSS 防护：后端 JSoup 白名单清洗 + 前端 DOMPurify 净化渲染。
- 路径穿越防护：附件文件名净化 + 路径包含校验。
- 登录限流：Redis 滑动窗口防暴力破解。
- 启动密钥校验：生产使用默认密钥时 fail-fast 拒绝启动。
- `/api/admin/**` 仅 `ROLE_ADMIN` 可访问。

---

## 运维脚本速查

### Windows（PowerShell / Batch）

| 脚本 | 用途 | 常用示例 |
|------|------|---------|
| `deploy\build.ps1` / `build.bat` | 编译打包 | `-Dev`(开发启动) / `--skip-tests` / 默认完整构建 |
| `deploy\start-service.bat` | 直接启动 | `--dev` / `--port 9090` |
| `deploy\service.ps1` / `service.bat` | 服务管理 | `start` / `stop` / `restart` / `status` |
| `deploy\inspect.ps1` | 系统巡检 | `-OutFile <报告路径>` |
| `deploy\backup.ps1` | 数据备份 | `-Retention 14` / `-SkipAttachments` |
| `deploy\logs.ps1` | 日志查看 | `-Follow` / `-Filter ERROR` |
| `deploy\package.ps1` | 制品打包 | `-Version 1.0.1` |
| `deploy\install.ps1` | 首次安装 | 无参生成配置 → `-Confirm` 正式安装 |
| `deploy\upgrade.ps1` | 版本升级 | `-ReleaseDir <目录>` |
| `deploy\uninstall.ps1` | 卸载 | `-PurgeData` 清除数据 |

### Linux（Bash）

| 脚本 | 用途 | 常用示例 |
|------|------|---------|
| `deploy/build.sh` | 编译打包 | `--frontend-only` / `--backend-only` / `--skip-tests` |
| `deploy/start-service.sh` | 直接启动 | `--dev` / `--port 9090` / `--background` |
| `deploy/service.sh` | 服务管理 | `start` / `stop` / `restart` / `status`（支持 systemd + PID） |
| `deploy/inspect.sh` | 系统巡检 | `--out-file report.txt` |
| `deploy/backup.sh` | 数据备份 | `--retention 14` / `--skip-attachments` |
| `deploy/logs.sh` | 日志查看 | `--follow` / `--filter ERROR` / `--stdout` |
| `deploy/mail-client.service` | systemd 单元 | 拷贝到 `/etc/systemd/system/` 后 `systemctl enable` |

详细用法见 [运维文档](doc/运维文档.md) 和 [DEPLOY.md](DEPLOY.md)。

---

## 已知限制 / 后续迭代

- OST 目前仅作为服务端工作存储使用，PST 为**单向导出**供 Outlook 读取（用户已确认，不做双向同步、不支持 Exchange）。
- IMAP IDLE 实时推送为可选增强，当前采用 60s 轮询 + WebSocket 事件推送兜底。
- PST 生成读写占用 java-libpst 接口，真实二进制导出建议后续叠加成熟 PST 写入库完善。