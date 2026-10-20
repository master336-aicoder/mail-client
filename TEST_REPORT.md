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

---

# 第二轮全量复测（2026-09-30）问题清单

> 范围：后端全部 REST 端点（正向 + 边界 + 越权 + 并发）+ 前端 7 个页面每一个按钮/输入/开关/下拉。
> 手段：Node API 套件（168 条用例轨迹）+ headless Chrome CDP 走查（fetch/XHR/console 全程监听）+ 后端日志 `logs/dev-msgcenter.log` 对照。
> 沙箱：用户 16 `e2euser` / 用户 17 `e2euser2`；真实邮箱账户（id=1、id=9）未做任何写操作。
> 本清单为「先列问题」阶段，尚未修复；状态列在修复过程中逐项更新。

## 6.1 后端 API / 安全（P0 越权与数据完整性）

| 编号 | 级别 | 问题 | 证据 | 状态 |
|---|---|---|---|---|
| B01 | P0 | 已认证 SSRF 未堵全：账户主机仍可填 `169.254.169.254`（云元数据） | `AccountService` 主机校验白名单缺 link-local；API 用例通过 | 待修 |
| B02 | P0 | `POST /api/folders` 接受客户端 `accountId`，可为他人账户建文件夹（跨用户写） | `FolderService.create` 直接 `save(folder)`，未校验 `accountId` 归属 | 待修 |
| B03 | P0 | 系统文件夹（INBOX）可被 `DELETE /api/folders/{id}` 删除 | 无 `seq`/系统位保护，实测删除成功 | 待修 |
| B04 | P0 | 合并虚拟文件夹（`accountId IS NULL`）可删除，破坏聚合视图 | 实测 folder 86 被删 | 待修 |
| B05 | P0 | `POST /api/transfer/export` IDOR：`folderIds` 不校验归属，用户 17 的邮件被导出到用户 16 可见产物 | `TransferController` 取了 `userId` 却未使用；task 19251 产物落在 `data/exports/user-17` | 待修 |
| B06 | P0 | 草稿接口接受他人 `accountId` | messageId 174 落库到用户 2 的账户 | 待修 |
| B07 | P0 | 草稿可携带他人附件 id，并回写他人 attachment 行的 `message_id` | att 39 归属被改写 | 待修 |
| B08 | P0 | `POST /api/attachments` 接受他人 `accountId` | att 40 → accountId 2 | 待修 |
| B09 | P1 | 分页参数未夹取：`page=0/-1`、`size=0/1000000` → `IllegalArgumentException` → 统一 50000 且泄露英文异常文案 | 列表类端点通用 | 待修 |
| B10 | P1 | `PUT /api/settings` 对值做裸 `(Number) v` 强转，字符串/数组直接 ClassCastException 500 | `SettingsController` | 待修 |
| B11 | P1 | 文件夹名 500 字符 → `Data too long for column 'name'` 500，未在参数校验拦截 | 后端日志 SqlExceptionHelper | 待修 |
| B12 | P1 | 上传缺少 multipart 必需部分、批量接口缺 `enabled` 参数 → Spring 原始异常 500（文案英文、非业务码） | `MissingServletRequestPartException` 等 | 待修 |
| B13 | P1 | 邮件列表 `accountId` 形参完全未使用，合并视图无法按账户过滤 | `MailService.list(…, Long accountId)` 体内无引用 | 待修 |
| B14 | P1 | 跨用户通知「标记已读」返回 `code=0`（未校验归属，静默无效） | `NotificationService.markRead` | 待修 |
| B15 | P1 | 通知/任务批量接口 ids 无数量上限（可提交 10 万 id） | 无 `@Size` 限制 | 待修 |
| B16 | P1 | 管理员设置角色无白名单：`'SUPERUSER'` 被原样持久化 | `AdminController.setRole` | 待修 |
| B17 | P1 | `GET /api/admin/users` 返回裸数组（未包裹 `ApiResponse`）且无分页，一次返回全部用户 | 前端已做兼容，契约仍不一致 | 待修 |
| B18 | P1 | 删除账户遗留孤儿 `folders` 行（未级联清理） | 探测账户 16 删除后 folder 115–118 残留 | 待修 |
| B19 | P1 | `GET /api/folders` 有副作用：读取过程自动建文件夹（测试中出现新 folder 143） | `FolderService` 懒建系统文件夹 | 待修 |
| B20 | P2 | `syncDailyTime='8:30'` 被拒（与 UI 允许输入不一致） | 校验正则过严 | 待修 |
| B21 | P2 | 详情 DTO 缺 `starred/read` 布尔位（只有 `flags` 位图），前端只能盲发 PUT flags | `MessageDetail` | 待修 |
| B22 | P2 | `/api/health` 未包裹统一响应体，与全局契约不一致 | `HealthController` | 待修 |
| B23 | P2 | 草稿文件夹判定按名称 `"草稿"` 硬编码，重命名/英文名即失效 | `MailService.list` | 待修 |
| B24 | P2 | 搜索 SQL 强制 `draft=false`，草稿箱内搜索恒空 | `MessageRepository.searchByFolder(s)` | 待修 |
| B25 | P2 | 收信认证持续失败无退避/熔断：真实账户 `zd@master336.cn` 每 30–60s 反复重试（535/Too many failed authentications），有被服务器封禁风险 | 后端日志 09:37–09:40 连续 WARN | 已修（见第 7 节） |
| B26 | P1 | 收信报错归类错误：POP3「Authentication requires an encrypted connection」「Too many failed authentications」被通用认证分支吞掉，一律提示「用户名或密码不正确」，把用户引去反复改授权码 | `MailErrors.hintFor` 分支顺序 + 后端日志两类真实文案 | 已修（见第 7 节） |

## 6.2 前端功能 / 交互

| 编号 | 级别 | 问题 | 证据 | 状态 |
|---|---|---|---|---|
| F01 | P0 | 草稿编辑后点「发送」不会删除原草稿，`@sent` 只关抽屉不刷新列表/计数 → 再点一次即重复投递 | `ComposeDrawer` 全文未使用 `draftId`；`MailView.vue:545` 传入即被丢弃、`:162` | 待修 |
| F02 | P1 | 「存草稿」恒新建：编辑已有草稿再存 → 重复草稿 | 运行时两次点击 = 2 条草稿（id 177/178） | 待修 |
| F03 | P1 | 写信附件在上传列表里删掉后，仍会被上传并随信发出（未接 `on-remove`） | `ComposeDrawer.vue:31,170` | 待修 |
| F04 | P1 | 阅读区「发送草稿」请求体没有 `attachments` → 带附件草稿发出后附件丢失 | `MailView.vue:561` vs `ComposeRequest` | 待修 |
| F05 | P1 | 顶栏「全部账户」筛选无效：跳 /mail 后 `router.replace` 丢掉 `?accountId=`，且后端本就不按 accountId 过滤 | `AppLayout.vue:204`、`MailView.vue:452`、B13 | 待修 |
| F06 | P1 | 文件夹无「重命名/删除」入口（后端 PUT/DELETE 已具备，`folderApi.remove` 无调用方） | `MailView.vue:166`、`api/index.ts:140` | 待修 |
| F07 | P1 | PST 导入是假功能：「开始导入」按钮没有 `@click`，后端也没有导入接口，页面文案却写「或从 PST/OST 文件导入」 | `TransferView.vue:57`、`TransferController` 仅 export/status | 待修 |
| F08 | P1 | 规则编辑保存 payload 无 `enabled`，后端用 primitive boolean 比较 → 停用规则被静默重新启用 | `RuleSettingsView.vue:185`、`RuleService.java:47` | 待修 |
| F09 | P1 | 规则优先级不可设置、列表无排序操作，却用 `i+1` 暗示优先级；`reorder` 接口从未调用（新建恒 priority=0） | `RuleSettingsView.vue:15-24`、`RuleService.findByUserIdOrderByPriorityAsc` | 待修 |
| F10 | P1 | 通用设置「实时推送」开关为纯局部状态：不加载、不提交，后端无对应字段，开关无任何作用 | `GeneralSettingsView.vue:21`、`UserSetting` 无 push 字段 | 待修 |
| F11 | P1 | 退出登录后 WebSocket 幽灵重连：`onclose` 先 `ws=null` 再 `setTimeout(connect,5000)`，`disconnect()` 无法取消 → 未授权状态下持续握手 | `stores/mail.ts:82-97` | 待修 |
| F12 | P2 | 主题模式：「浅色」与「跟随系统」渲染完全相同（都置空 class），且 `.dark-mode` 只设 `color-scheme`，真实配色由 `prefers-color-scheme` 决定 → 选深色可能无变化 | `GeneralSettingsView.vue:190-194`、`styles/tokens.css:39-53` | 待修 |
| F13 | P2 | `loadSync` 失败静默套默认值，用户无感知；随后点保存会把默认值写回覆盖真实配置 | `GeneralSettingsView.vue:147-164` | 待修 |
| F14 | P2 | 「记录策略」保存总带 `defaultSyncIntervalSec`；GET 失败兜底 60 → 保存策略会悄悄改回收信频率 | `TasksView.vue:449-473` | 待修 |
| F15 | P2 | 切换文件夹/刷新不清空搜索关键词，列表显示「暂无邮件」而实际有数据 | 运行时 `/api/folders/84/messages?q=qa-dup-probe` → 0 行，草稿实际存在 | 待修 |
| F16 | P2 | `el-pagination` 未绑定 `current-page`：搜索/切文件夹后已回第 1 页，页码仍高亮旧页，再点该页无反应 | `MailView.vue:105` | 待修 |
| F17 | P2 | `MAIL_NEW` 推送按 page=1、无关键词重载，覆盖用户当前搜索/翻页结果 | `stores/mail.ts:71` | 待修 |
| F18 | P2 | 新建文件夹「全部账户」= `accountId null` 的虚拟文件夹：不能存邮件、不能作为移动目标，建出来永远为空 | `MailView.vue:170,613`、移动候选排除 `accountId==null` | 待修 |
| F19 | P2 | 深链 `/mail/message/:id` 失效：路由参数 `:id` 与 `watch(route.params.messageId)` 不一致，且把邮件 id 当文件夹 id 请求 | `router/index.ts:17`、`MailView.vue:385,624` | 待修 |
| F20 | P2 | 阅读区删除、抽屉发送后不重载列表与侧栏计数，需手动刷新 | `MailView.vue:162,522` | 待修 |
| F21 | P2 | 管理页「服务状态」写死 服务端 UP / 数据库 UP / 附件存储 本地，从不调 `/api/health` | `AdminView.vue:66-79`，运行时确认恒 UP | 待修 |
| F22 | P2 | 全站无角色管理入口：`adminApi.setRole` 无调用方，后端 `PUT /admin/users/{id}/role` 为死接口，无法授予/撤销管理员 | `api/index.ts:385`、`AdminView.vue:11-15` | 待修 |
| F23 | P2 | 消息中心点开单条：本地先置已读，`markRead` 失败被 `.catch(()=>{})` 吞掉 → 行状态与未读徽章不一致且无提示 | `MessagesView.vue:133-137` | 待修 |
| F24 | P2 | 执行记录抽屉切换任务未清理上一任务的导出提示与轮询定时器 | `TasksView.vue:348-355,385` | 待修 |
| F25 | P2 | 账户向导「完成并验证」不校验连通；保存后仅清 `email`，host/用户名/密码残留 → 下一个账户带上一账户的服务器与密码 | `AccountSettingsView.vue:102,527-545` | 待修 |
| F26 | P2 | 账户「保存频率」成功后不重载列表，紧接着点「编辑」显示旧频率 | `AccountSettingsView.vue:560-573,601` | 待修 |
| F27 | P2 | 写操作缺并发锁/loading：规则「保存」与「存草稿」可双击各发一次请求造成重复资源 | `RuleSettingsView.vue:73,169`、`ComposeDrawer.vue:235` | 待修 |
| F28 | P2 | 「安装 PWA」使用原生 `alert()`，与 Element Plus 提示风格不一致，且会阻塞页面脚本 | `GeneralSettingsView.vue:227` | 待修 |

## 6.3 本轮验证为「正常」的项（避免重复排查）

- 7 个路由加载全部 200，无 console 报错、无异常 toast。
- CSRF 缺失的写请求、跨用户账户 PUT/DELETE/test、`/sync?accountId=他人`、带他人 accountId 的发送、跨用户批量操作、跨用户附件下载 → 均被正确拒绝。
- 登录错误提示不泄露「用户是否存在」；账户列表按用户隔离。
- 批量条按钮无选中时正确禁用；`POST /api/messages/batch-flags` 生效后选择态清空、列表与文件夹计数刷新。
- 「整个文件夹 → 清空该文件夹」有二次确认且文案明确不可恢复；「清空已读」同。
- 后台任务执行记录抽屉：全选 → 导出选中 → `POST /api/tasks/runs/batch-export` → 轮询 → 提示产物位置，链路完整无报错。
- 非管理员直接 pushState 访问 `/admin` 会被路由守卫弹回邮件页；后端 `/api/admin/**` 确有 `hasRole("ADMIN")`。
- 通用设置「保存」`PUT /api/settings` 200；「保存新密码」空值与两次不一致均有前端校验。
- 新建文件夹空名有校验（「请输入文件夹名称」）。

## 6.4 未覆盖项（受数据条件限制）

- 规则「编辑/删除/停用」按钮：沙箱无规则数据（将先造数据再验）。
- 批量「移动到 / 未读 / 取消星标 / 删除」：同族 `batch-flags` 链路已验证，其余按 API 用例结论。
- 分页高亮错位（F16）需 >50 封邮件才能肉眼复现，当前沙箱仅 8 封。
- 消息中心单条已读/删除（F23）本轮无通知数据，仅有代码级证据。

## 7. 专项：收信报「密码不正确」而发信正常（B25 / B26，2026-09-30 已修）

现象：同一账户、同一份用户名+授权码（`MailAccount` 只有 `username`/`secretEncrypted` 一对凭据，收信发信共用），
收信提示「认证失败：用户名或密码不正确」，发信测试却「连接与认证成功」。

排查结论（不是密码真的不一致，而是三处实现问题）：

1. **B26 报错归类**：`MailErrors.hintFor` 里通用认证分支排在最前，且条件含 `authenticationfailedexception`，
   于是 POP3 在 110 端口明文认证时服务器回的 `Authentication requires an encrypted connection; use STLS or port 995`
   和限流回复 `Too many failed authentications, retry in 292s`（两者都被 JavaMail 包成 `AuthenticationFailedException`）
   一律被翻译成「密码不正确」。发信走 25 端口不加密 + 当时尚未触发限流，所以显得「密码是对的」。
2. **B25 无退避**：`TlsProps` 对 `NONE` 不下发任何属性，明文 AUTH 必然失败；周期收信按 60s 一轮反复撞，
   服务商按账号计数且收发共用，最终把 SMTP 一起锁在外面（日志里已出现 `SMTP … 535 Too many failed authentications`）。
3. **失败原因在包装中丢失**：`Pop3/ImapMailClient`、`SmtpTransport` 把 JavaMail 异常换成只含中文文案的 `BizException`，
   既让上层无法判断失败类型，也没有连接/读超时——实测服务器不回包时任务线程被永久挂住。

修复：

- `MailErrors`：在通用认证分支之前依次判定「服务器要求在加密连接上认证」「该端口不支持 STARTTLS」「服务商认证限流」
  （限流回复中的 `retry in Ns` 会解析成「约 N 秒后自动解除」，并说明该计数收发共用）；
  新增 `isAuthRejected` / `retryAfterSeconds`，顶层中文提示优先原样透传。
- `BizException` 支持携带 cause，三个协议层抛出点全部带上原始异常；`TlsProps` 统一
  `connectiontimeout=15s / timeout=60s`（SMTP 另加 `writetimeout=60s`）。
- `TaskService.deferAfterAuthFailure`：认证被拒的周期收信任务把 `nextRunAt` 推到至少 10 分钟后
  （服务器给了冷却时长则按「N+60s」，上限 1 小时），非认证类失败仍按原频率。
- `MailClientApplication` 抽出 `SchedulingConfiguration`，`--mail.app.scheduling-enabled=false` 可让进程提供服务但不跑后台调度，
  便于验证单个账户而不产生真实邮箱流量。

验证（本地假 POP3 服务器 `127.0.0.1:1110/1111/1112` 分别回「要求加密」「限流 retry in 45s」「Invalid credentials」，
`127.0.0.1:1113` 无监听；e2euser 沙箱账户，未触碰真实邮箱）：

- `POST /api/accounts/test-config` 三种失败分别返回上述三类新文案，真密码错仍返回「用户名或密码不正确」。
- 限流账户手动执行收信任务 → `tasks.next_run_at` 由 +60s 变为 +10min，日志出现「认证被拒，下一次执行退避至 …」；
  连接被拒（非认证类）账户仍为 +60s。
- 真实账户 `zd@master336.cn` 重启后首个失败轮即触发退避（10:42:17 认证被拒 → 下次 10:52:17），
  本轮日志中该类外连次数由每分钟 1 次降为每 10 分钟 1 次。
- 服务器不回包时不再永久挂起：POP3 读线程 60s 超时后返回「连接超时：服务器无响应…」。

用户侧仍需处理：`master336.cn` 的 POP3 目前回 `[AUTH] Invalid credentials`，说明服务商端该授权码确已失效或被锁，
需要按新提示改为 SSL(995)/STARTTLS 或在邮箱后台重新生成授权码；仅靠客户端重试不会恢复。

## 8. 功能新增：邮件转发（收件箱 / 已发送 / 草稿箱，2026-09-30）

阅读区新增「转发」按钮，对收件箱、已发送、草稿箱的邮件均可用（草稿是「以此内容另起一封」，不回写原草稿）。

实现要点：

| 层 | 改动 | 说明 |
|---|---|---|
| 前端 | `MailView.openForward()` | 主题加 `Fwd:` 前缀；引用块逐行 `<p>`（转发标记 + 发件人/日期/主题/收件人，字段经 `escapeHtml`）；正文复用阅读区 `displayBody`（已 cid 改写 + DOMPurify 净化），无 XSS 回归；发件账户默认取该邮件所属 `detail.accountId` |
| 前端 | `ComposeDrawer` 带入附件 | 附件区以可删除标签展示原邮件附件并提示「发送时会另存一份」；提交（发送/存草稿）时才逐个 `POST /api/attachments/{id}/copy`，`copiedId` 缓存避免「先存草稿再发送」重复复制；删掉的标签不再复制 |
| 后端 | `AttachmentService.copy` + `POST /api/attachments/{id}/copy` | 只新建元数据记录、共用 `storageRef`（存储从不物理删除）。**必须复制**：发送会把请求里引用的附件 `message_id` 改挂到新邮件，直接传原 id 会让原邮件附件列表变空 |
| 复用 | 内联图片不需要复制 | 正文里的 `/api/inline/{id}`、`/api/attachments/{id}/content` 由 `SmtpTransport.loadInlineImages` 扫描并 `rewriteInlineSrc` 改写成 `cid:{id}@mail-client`，图片字节随 MIME `multipart/related` 一起发出，原附件行不被改挂 |

验证（沙箱 user 16 / 账户 14 `ui-check@local.test`，SMTP 指向本地假服务器 `127.0.0.1:11025`；`--mail.app.scheduling-enabled=false` 启动，未触碰真实邮箱）：

- 三类文件夹阅读区按钮实测：收件箱 `转发|回复|删除`、已发送 `转发|回复|删除`、草稿 `转发|编辑|发送|删除`。
- 转发抽屉：标题「转发邮件」、主题 `Fwd: Original with attachment A`、带入标签 `att-a.txt（1 KB）`、正文含引用头 + 原文 `original body for forward test`。
- 真实投递：假 SMTP 收到 `Fwd: Original with attachment A`，`multipart/mixed` 内含 `Content-Disposition: attachment; filename=att-a.txt`，正文为逐行引用 + 原文。
- 附件归属（关键回归点）：`attachment_meta` 中 41 仍挂在原邮件 182，副本 45 挂到转发件 184；草稿路径同样验证（副本 47 → 草稿 188，41 未动）。
- 内联图片：转发带图邮件后，假 SMTP 收到 `multipart/related` + `Content-ID: <46@mail-client>` + base64 的 `px.png`，正文 `<img src="cid:46@mail-client">`，原附件行未被改挂。
- 发送账户默认值来自该邮件账户（此前恒取列表第一个账户，会选中 STARTTLS/其它端口导致 TLS 握手失败）。
