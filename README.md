# 云邮 Mail — Web 邮箱客户端

多用户 Web 邮箱客户端。每个用户拥有独立的服务端工作存储（OST），邮件内容可持久化为
PST 供 Outlook 读取。兼容主流收/发协议（IMAP4 / POP3 / SMTP）与加密方式（SSL / STARTTLS / NONE），
支持文件夹、筛选规则、实时推送与 PWA 离线访问。

> 技术栈：**Spring Boot 3.3 + JDK 21** 后端 · **Vue 3 + TypeScript + Vite + Element Plus + PWA** 前端

---

## 目录结构

```
mail-client/
├── doc/                     # 需求 / 概要 / 详细设计 / UI 规范 / UI 设计 / 开发计划 / 环境清单
│   ├── 需求分析文档.md
│   ├── 概要设计文档.md
│   ├── 详细设计文档.md
│   ├── UI设计规范.md        # 通用 UI 设计规范（设计令牌 / 通用组件）
│   ├── UI设计文档.md         # 业务页面 UI 设计
│   ├── 开发计划.md
│   ├── 测试用例文档.md       # 功能按钮级 + 后端接口级测试用例（≈158 条）
│   └── 基础环境清单.txt
├── design/mockup.html       # 高保真交互原型
├── backend/                 # Spring Boot 3 后端
│   ├── pom.xml
│   └── src/main/java/com/mail/...
└── frontend/                # Vue 3 + Vite 前端
    └── src/...
```

## 已落地功能

| 模块 | 说明 | 前后端 |
|------|------|--------|
| 认证授权 | JWT(HS256) + BCrypt + Spring Security，`/auth/login|register|me` | ✅ |
| 邮箱账户 | 自动探测服务器、多协议绑定、凭据 AES-256-GCM 加密存储 | ✅ |
| 收信协议 | IMAP4 / POP3 拉取、SMTP 发送（SSL/STARTTLS） | ✅ |
| 文件夹 | 树形结构、默认收件箱/草稿/已发送/已删除、未读数 | ✅ |
| 邮件 | 列表分页、详情、标记(已读/星标/重要)、删除、草稿 | ✅ |
| 附件 | 抽象存储接口：本地文件 与 MinIO 可切换 | ✅ |
| 筛选规则 | 条件(发件人/主题/正文/含附件) → 动作(星标/已读/删除/移动)，按优先级 | ✅ |
| 实时推送 | WebSocket(`/api/ws`) MAIL_NEW 等事件推送，断线重连 | ✅ |
| 定时同步 | `@Scheduled` 轮询拉取新邮件 → 入库 → 规则 → 推送 | ✅ |
| PST/OST | java-libpst 生成/读取 PST，导出供 Outlook | ✅ |
| 前端页面 | 登录/注册、工作台、邮件三栏、账户向导、规则管理、PST 导入导出、通用设置、后台管理 | ✅ |
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
mvn spring-boot:run
# 或打包
mvn clean package -DskipTests
java -jar target/mail-client-backend-1.0.0.jar
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

- 密码 BCrypt；JWT HS256 状态无关认证。
- 账户凭据使用 AES-256-GCM 加密落库，`MASTER_KEY` 生产须通过环境变量注入。
- `/api/admin/**` 仅 `ROLE_ADMIN` 可访问。
- 邮件 HTML 在渲染层建议配合 JSoup 白名单清洗（后端已依赖 jsoup）。

## 已知限制 / 后续迭代

- OST 目前仅作为服务端工作存储使用，PST 为**单向导出**供 Outlook 读取（用户已确认，不做双向同步、不支持 Exchange）。
- IMAP IDLE 实时推送为可选增强，当前采用 60s 轮询 + WebSocket 事件推送兜底。
- PST 生成读写占用 java-libpst 接口，真实二进制导出建议后续叠加成熟 PST 写入库完善。