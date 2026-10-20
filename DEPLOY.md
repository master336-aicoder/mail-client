# mail-client 部署文档（Windows 服务器）

本仓库是 Web 邮箱客户端：Spring Boot 3.3.4（JDK 21）后端 + Vue 3 SPA 前端。
生产部署采用 **单进程同源托管**：打包时把前端 `dist` 拷入 jar 的 `classpath:/static`，
由后端一个进程同时提供页面、`/api`、`/api/ws`；后端通过 **NSSM** 注册为 Windows 服务。

```
浏览器 ──http(s)──▶ 后端服务 :8080
                     ├─ /            前端 SPA（history 回退 index.html）
                     ├─ /assets/**   前端静态资源
                     ├─ /api/**      REST 接口
                     └─ /api/ws      WebSocket 推送
外部依赖：MySQL 8（库表由 Flyway 自动建）、Redis（会话/限流）
```

---

## 1. 前置条件（目标服务器）

| 组件 | 版本/位置（基线见 `doc/基础环境清单.txt`） | 说明 |
|------|------|------|
| JDK | 21，`D:\Java\jdk-21` | 运行 jar |
| MySQL | 8.x `localhost:3306`，root/root | 需一个有建库权限的账号 |
| Redis | `D:\Redis-x64-3.2.100`，端口 6379，密码 `redis` | 已作为服务/进程运行 |
| NSSM | 任选路径，如 `C:\mail-client\tools\nssm.exe` | 将 jar 注册为 Windows 服务 |
| 构建机（可选） | Maven `D:\apache-maven-3.6.3`、Node v22 | 仅打包时需要；若直接用他人交付的 zip 则服务器无需装 |

- MySQL、Redis 必须已在服务器上运行。
- 打包与安装均使用 PowerShell（建议 5.1+ 或 7.x），安装/升级/卸载需 **管理员权限**。
- 若服务器无外网 npm，请在有网构建机上跑 `package.ps1`，再把 zip 拷到服务器。

## 2. 打包制品（在构建机执行）

```powershell
powershell -ExecutionPolicy Bypass -File deploy\package.ps1 -Version 1.0.1
```

产出（默认在 `dist-release\`）：
- `mail-client-1.0.1\` 目录 与 `mail-client-1.0.1.zip`
- 目录结构：`app\mail-client-backend-1.0.1.jar`（已内嵌前端）、`config\`（模板）、
  `scripts\`（install/upgrade/uninstall/_common）、`docs\DEPLOY.md`、`VERSION.json`（含 jar SHA256）。

参数：`-SkipFrontend` 复用现有 `frontend\dist`；`-JavaHome`、`-MavenCmd`、`-OutDir` 覆盖路径。

把 `mail-client-<版本>.zip` 上传到服务器，解压到任意目录（如 `C:\deploy\mail-client-1.0.1`）。

## 3. 安装（首次部署，在服务器执行）

```powershell
cd C:\deploy\mail-client-1.0.1\scripts
# 第一步：生成 settings.json（写入随机 JWT_SECRET / MASTER_KEY）后退出
powershell -ExecutionPolicy Bypass -File .\install.ps1
# 编辑 config\settings.json：核对数据库/Redis 账号、publicOrigin、端口、路径
# 第二步：正式安装
powershell -ExecutionPolicy Bypass -File .\install.ps1 -Confirm
```

`install.ps1 -Confirm` 会依次：
1. 校验管理员权限、定位 `java`/`nssm`/jar；
2. 创建 `installDir`（默认 `C:\mail-client`）下 `app/config/logs/backups/data\*`；
3. 拷贝 jar；用 `settings.json` 渲染 `config\application-prod.yml`；
4. `CREATE DATABASE IF NOT EXISTS mail_client`（表由 Flyway 在首启时建）；
5. 注册 NSSM 服务：工作目录=installDir、`--spring.profiles.active=prod`、stdout/stderr 落 `logs\`、
   开机自启，并通过 **服务环境变量** 注入 `JWT_SECRET`/`MASTER_KEY`（不写入 yml）；
6. 启动服务并轮询 `/api/health`，通过后打印访问入口。

### settings.json 关键字段

| 字段 | 说明 |
|------|------|
| `publicOrigin` | **本系统对外根地址**，如 `http://mail.corp:8080` 或 `https://mail.corp`。同源部署时它是 WebSocket 握手来源校验与 CORS 允许来源，必须与实际浏览器地址一致，否则实时推送连不上。 |
| `authCookieSecure` | HTTPS 部署置 `true`（鉴权 Cookie 加 `Secure`）；纯 http 置 `false`。 |
| `allowPrivateHost` | SSRF 防护。仅当所连邮件服务器为内网/回环主机时置 `true`，生产默认 `false`。 |
| `storageMode` | `local`（文件存 `data\`）或 `minio`。选 `minio` 时需在 `application-prod.yml` 追加 `mail.storage.minio.*` 并保证凭据非默认。 |
| `jwtSecret`/`masterKey` | 自动生成；`masterKey` 用于账号密码加解密，**升级时切勿更换**，否则已存邮箱凭据无法解密。 |
| `db`/`redis` | 连接信息；`db.mysqlBin` 用于自动建库，`mysqldump.exe` 同目录用于备份。 |

## 4. 升级（新版本）

用新交付的 zip 解压到独立目录，执行：

```powershell
cd C:\deploy\mail-client-1.0.2\scripts
# 复用已存在的 settings.json（默认在 scripts\config\settings.json）
powershell -ExecutionPolicy Bypass -File .\upgrade.ps1 -ReleaseDir C:\deploy\mail-client-1.0.2
```

`upgrade.ps1` 流程：
1. **备份**：快照旧 jar/配置到 `installDir\backups\<时间戳>\`，`mysqldump` 备份数据库；
2. **换件**：停服务 → 替换 jar → 用 `settings.json` 重渲染 `application-prod.yml`；
3. **迁移**：启动服务，**Flyway 自动执行增量迁移**（应用 `ddl-auto: validate`，只校验不改表）；
4. **健康检查**：轮询 `/api/health`；失败则 **自动回滚** 旧 jar/配置并重启（数据库回滚需按提示用备份手动恢复）。

参数：`-SettingsFile` 指定配置（默认取 `scripts\config\settings.json`）；`-SkipBackup`、`-SkipHealthCheck`。
> 建议：升级前用 `-SettingsFile` 指向服务器上真实生效的 settings.json，保持 `masterKey` 不变。

## 5. 回滚 / 卸载

- 升级失败已自动回滚；手动回滚：停服务，把 `backups\<ts>\` 内旧 jar 放回 `app\`、旧 `application-prod.yml` 放回 `config\`，如需 DB 还原用备份 SQL 导入，再启动。
- 卸载（保留数据与库）：
  ```powershell
  powershell -ExecutionPolicy Bypass -File .\uninstall.ps1
  ```
  加 `-PurgeData` 才会删除安装目录；数据库 **永不** 自动删除。

## 6. 运维速查

```powershell
nssm status mail-client
nssm restart mail-client
nssm stop mail-client ; nssm start mail-client
# 日志
Get-Content C:\mail-client\logs\stderr.log -Tail 100 -Wait
Get-Content C:\mail-client\logs\stdout.log -Tail 100
```

## 7. HTTPS 与反向代理（可选）

单进程部署下，应用直接监听 `:8080`（http）。生产如需 HTTPS，建议前置 IIS/Nginx/网关做 TLS 终止并反代到 `127.0.0.1:8080`，转发 `Upgrade`/`Connection` 头以支持 `/api/ws`，然后把 `settings.authCookieSecure=true`、`publicOrigin=https://<域名>` 重跑 `upgrade.ps1` 更新配置。

## 8. 安全默认（与渗透测试整改一致）

- 启动密钥校验：生产 `allow-insecure-defaults=false` 且未注入真实 `JWT_SECRET`/`MASTER_KEY` 时 fail-fast。
- 鉴权：`access_token` 为 HttpOnly + SameSite=Lax Cookie；写操作走 `X-CSRF-Token` 双重提交。
- SSRF：`allow-private-host=false` 时拦截内网/回环邮件主机。
- 附件：文件名净化 + 路径包含校验，防目录穿越。
- 前端正文经 DOMPurify 净化渲染。

## 9. 故障排查

| 现象 | 排查 |
|------|------|
| 服务起不来 / 健康检查失败 | 看 `logs\stderr.log`。多为 DB/Redis 连接或密钥缺失：确认 `application-prod.yml` 中 db/redis，且服务环境变量含 `JWT_SECRET`/`MASTER_KEY`。 |
| 打开页面 404 或空白 | 确认打包时把 `dist` 拷入了 jar（`package.ps1` 步骤 2）；`jar tf` 应含 `BOOT-INF/classes/static/index.html`。 |
| 刷新深链 404 | 需 `SpaWebConfig` 的 history 回退；确认使用的是含该改动的构建。 |
| 实时收信不连 | `publicOrigin` 与浏览器地址不一致导致 WS 握手被拒；改成完全一致的 scheme+host+port。 |
| 登录报 CSRF(40301) | 反向代理需透传 Cookie；HTTPS 未置 `authCookieSecure=true` 也会被浏览器拦。 |
| 换 masterKey 后邮箱凭据解密失败 | 回滚到旧 `masterKey`；该密钥不可轮换。 |
