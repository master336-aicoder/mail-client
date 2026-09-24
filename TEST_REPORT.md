# 邮件客户端 全量功能与 API 测试报告

> 生成时间：2026-09-24 · 分支：master · 范围：后端全部 REST API + 前端主要页面/交互

## 1. 测试环境与方式

| 项 | 值 |
|---|---|
| 后端 | Spring Boot 3.3 / JDK 21，端口 8080 |
| 数据库 | MySQL 8（库 `mail_client`）、Redis 6379 |
| 前端 | Vue 3 + Vite（端口 5173，代理 `/api`→8080） |
| API 集成测试 | `apitest.sh`（curl，覆盖 14 个 Controller / 42 个端点）|
| 前端走查 | Qoder Browser Connector（真实浏览器，监测 console 异常）|
| 真实账号验证 | gientech 邮箱 IMAP 收件 / 详情页渲染 / 分页 |

## 2. 测试结果汇总

| 项目 | 结果 |
|---|---|
| API 集成用例 | **49 / 49 通过（0 失败）** |
| 后端运行日志 | **0 条 ERROR** |
| 前端构建（含 vue-tsc 类型检查） | **通过** |
| 浏览器 console（各主要路由） | 干净；`/admin` 仅出现预期内的 `403`（普通用户越权），已统一处理 |

覆盖模块：认证、账户、文件夹、邮件列表/详情/旗标/删除、写信/存草稿/附件上传、同步任务、规则、导入导出、内嵌资源、管理员用户管理、安全边界（401/403、越权 IDOR、空值校验）。

## 3. 发现并修复的缺陷（共 7 处，涉及 7 个源文件）

### 后端
1. **匿名访问返回 403 且响应体为空** — `SecurityConfig.java`
   注册自定义 `AuthenticationEntryPoint`（401 / code 40100）与 `AccessDeniedHandler`（403 / code 40300），统一返回 JSON 信封。
2. **越权读取他人文件夹邮件（IDOR）** — `MailService.java`
   `list()` 增加文件夹归属校验，非本人文件夹抛 `BizException(40300, "无权访问")`。
3. **空文件夹名被接受** — `FolderService.java`
   `create`/`update` 增加空白名校验。

### 前端
4. **硬编码默认文件夹 `1`（属他人）导致“无权访问”弹窗 + mounted 未捕获异常** — `MailView.vue`
   登录后解析当前用户自己的“收件箱”合并夹并 `router.replace`，`load()` 包 try/catch。
5. **正文内嵌图片 `cid:` 引用无法显示** — `MailView.vue`
   `renderBody()` 建立 cid→附件映射，重写 `src/href="cid:X"` 与 `url(cid:X)` 为 `/api/inline/{id}`；无字节时回退占位图。
6. **403 被误判为登录失效导致强制登出** — `http.ts`
   区分 401（清 token、跳登录）与 403（仅提示“没有权限访问”，保留会话）。
7. **`AdminView` mounted 越权时未捕获异常** — `AdminView.vue`
   加载失败交由拦截器统一提示，避免未捕获 Promise。

附带修复 2 处弃用告警：`el-pagination` 的 `small`→`size="small"`、`el-link` 的 `:underline="false"`→`underline="never"`（`MailView.vue` / `AppLayout.vue`）。

## 4. 已知遗留（低优先级，本次未改）

- 路由 `/mail/message/:id` 的参数名与 `MailView` 中 `watch(route.params.messageId)` 不一致；当前无页面跳转使用该深链，属死路由。
- 历史邮件的内嵌图片字节在早期收件时未持久化，无法回填；前端已以占位图降级显示。
- 种子 `admin` 用户角色为 `USER`（按 RBAC 设计，管理员导航对非管理员隐藏）；正向管理员用例经 SQL 提权的临时用户验证通过（全部 200）。

## 5. 结论

所有测试用例通过，前后端异常均已消除，本轮修复闭环完成。
