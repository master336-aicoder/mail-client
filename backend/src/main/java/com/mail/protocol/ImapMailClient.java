package com.mail.protocol;

import com.mail.account.domain.MailAccount;
import com.mail.common.CryptoService;
import com.mail.common.MailErrors;
import com.mail.mail.domain.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.mail.Folder;
import jakarta.mail.FetchProfile;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.InternetAddress;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * IMAP4 客户端：连接、登录、拉取收件箱。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImapMailClient implements MailClient {

    private final CryptoService cryptoService;
    private final com.mail.config.AppProperties appProperties;

    /**
     * 每次同步仅拉取最近若干封：避免全量遍历整箱（大箱子下会长时间阻塞、超时）。
     * 60s 轮询场景下，最近窗口足够覆盖新邮件；历史邮件由首屏之外的其它机制处理。
     */
    private static final int RECENT_WINDOW = 50;

    /**
     * 单次 IMAP 拉取的「空闲」超时（秒）：连续这么多秒没有任何解析心跳才强关连接。
     * 旧写法是一次性墙钟覆盖整轮 50 封窗口，慢速企业网关（不返回 BODYSTRUCTURE、逐封走
     * 原始 MIME 回退）常在解析第 2 封前就被打断，导致最新一封入库、其余全部空主题空正文。
     * 改为「心跳式空闲超时」：每封邮件开解前 touch()，把强关时钟重新对齐到当前 + 120 秒，
     * 只惩罚真正挂死的 socket 读，不惩罚累计的正常处理时间。
     */
    private static final int FETCH_TIMEOUT_SEC = 300;

    /** 守护线程池：仅用于在超时后关闭挂起的 IMAP 连接，不占用业务线程。 */
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "imap-fetch-watchdog");
                t.setDaemon(true);
                return t;
            });

    private Properties propsOf(MailAccount account) {
        Properties props = new Properties();
        String host = account.getHost();
        String enc = account.getEncryption() == null ? "SSL" : account.getEncryption();
        // 默认端口按加密方式区分：SSL=993 隐式 TLS；STARTTLS/NONE=143 明文起连
        int defaultPort = "SSL".equalsIgnoreCase(enc) ? 993 : 143;
        int port = account.getPort() != null ? account.getPort() : defaultPort;
        props.put("mail.store.protocol", "imap");
        props.put("mail.imap.host", host);
        props.put("mail.imap.port", String.valueOf(port));
        TlsProps.apply(props, "mail.imap", enc, account.isIgnoreCertError());
        // 关键：禁用 partial fetch 并提高 fetchsize，确保 getContent() 返回完整的
        // String/Multipart 而非分段的 InputStream（否则正文解析会得到 null）
        props.put("mail.imap.partialfetch", "false");
        props.put("mail.imap.fetchsize", "1048576");
        // 显式设置连接/读/写超时：避免在慢速或异常消息上无限挂起，保证单次同步总能完成
        props.put("mail.imap.connectiontimeout", "15000");
        props.put("mail.imap.timeout", "30000");
        props.put("mail.imap.writetimeout", "15000");
        // 让 IMAP 客户端自动上报 ID（RFC 2971）；163/QQ/Gmail/Aliyun 会据此放行连接，
        // 否则会视为未知 IMAP 客户端而限流或断开。
        props.put("mail.imap.idname", ID_NAME);
        props.put("mail.imap.idversion", ID_VERSION);
        props.put("mail.imap.idprefix", "");  // 不加 "IMAP" 前缀，与常见客户端命名一致
        return props;
    }

    /** 上报给 IMAP 服务器的客户端标识；一些服务商据此放行，未上报会被限流。 */
    static final String ID_NAME = "MailClient";
    static final String ID_VERSION = "1.0.0";

    /**
     * 连接建立后主动发一次 IMAP ID（部分服务商不会自动响应 mail.imap.idname，只在收到 ID 命令后才解除限流）。
     * 反射调用 IMAPStore.id(Map)，非 Angus/Eclipse 实现忽略即可。
     */
    private void sendImapId(Store store) {
        try {
            java.util.Map<String, String> id = new java.util.LinkedHashMap<>();
            id.put("name", ID_NAME);
            id.put("version", ID_VERSION);
            id.put("vendor", "mail-client");
            java.lang.reflect.Method m = store.getClass().getMethod("id", java.util.Map.class);
            m.invoke(store, id);
        } catch (NoSuchMethodException ignore) {
            // 该 IMAP 实现无 id(Map)：多半已通过 props 自动上报，或服务器不敏感
        } catch (Exception e) {
            log.debug("IMAP ID 上报失败: {}", e.getMessage());
        }
    }

    private String password(MailAccount account) {
        return cryptoService.decrypt(account.getSecretEncrypted());
    }

    /** 显式传入的口令优先（未保存的配置测试）；为空则解密账户已存凭据。 */
    private String password(MailAccount account, String provided) {
        return provided != null && !provided.isEmpty() ? provided : password(account);
    }

    @Override
    public String testConnection(MailAccount account, String plainPassword) {
        Properties props = propsOf(account);
        int port = account.getPort() != null ? account.getPort()
                : ("SSL".equalsIgnoreCase(account.getEncryption()) ? 993 : 143);
        try (Store store = Session.getInstance(props).getStore("imap")) {
            store.connect(account.getHost(), port, account.getUsername(), password(account, plainPassword));
            sendImapId(store);
            return null;
        } catch (Exception e) {
            log.warn("IMAP 连接失败 {}: {}", account.getEmail(), e.getMessage());
            return MailErrors.explain(e, "IMAP " + account.getHost() + ":" + port);
        }
    }

    @Override
    public List<Message> fetchInbox(MailAccount account) {
        return fetchInbox(account, 0);
    }

    @Override
    public List<Message> fetchInbox(MailAccount account, int sinceSeq) {
        List<Message> result = new ArrayList<>();
        Store store = null;
        try {
            store = openStore(account);
            result.addAll(withWatchdog(store, (s, beat) -> {
                Folder inbox = s.getFolder("INBOX");
                return fetchWindow(inbox, account, sinceSeq, beat);
            }));
        } catch (Exception e) {
            log.warn("IMAP 拉取失败 {}: {}", account.getEmail(), e.getMessage());
            // 一封都没取到说明连接/登录/选箱阶段就失败了，必须让任务显性失败；
            // 已取到部分则保留成果（多为看门狗打断超大邮箱的逐封解析），下次同步续拉。
            if (result.isEmpty()) {
                throw new com.mail.common.BizException(50002,
                        MailErrors.explain(e, "IMAP " + account.getHost() + ":"
                                + (account.getPort() != null ? account.getPort() : 993)), e);
            }
        } finally {
            closeQuietly(store);
        }
        return result;
    }

    /**
     * 同步服务器「已发送」箱：用 LIST 探测已发送文件夹（含 SPECIAL-USE \Sent 标志与常见命名），
     * 命中后按窗口拉取。找不到已发送箱不算失败（返回空），只有连接/登录异常才向上抛。
     */
    @Override
    public List<Message> fetchSent(MailAccount account, int sinceSeq) {
        List<Message> result = new ArrayList<>();
        Store store = null;
        try {
            store = openStore(account);
            String sentName = findSentFolderName(store);
            if (sentName == null) {
                log.info("账户 {} 未发现已发送箱，跳过已发送同步", account.getEmail());
                return result;
            }
            final String name = sentName;
            result.addAll(withWatchdog(store, (s, beat) -> fetchWindow(s.getFolder(name), account, sinceSeq, beat)));
        } catch (Exception e) {
            // 已发送同步是附加能力：连接已就绪但拉取失败时保留已取部分，不阻断收件主流程
            if (result.isEmpty()) {
                throw new com.mail.common.BizException(50003,
                        MailErrors.explain(e, "IMAP 已发送 " + account.getHost()), e);
            }
        } finally {
            closeQuietly(store);
        }
        return result;
    }

    @Override
    public int fetchInboxInto(MailAccount account, int sinceSeq,
                              java.util.function.Consumer<Message> sink) {
        int[] n = {0};
        Store store = null;
        try {
            store = openStore(account);
            withWatchdog(store, (s, beat) -> {
                fetchWindow(s.getFolder("INBOX"), account, sinceSeq, beat, m -> { sink.accept(m); n[0]++; });
                return null;
            });
        } catch (Exception e) {
            log.warn("IMAP 流式收件失败 {}: {}", account.getEmail(), e.getMessage());
            if (n[0] == 0) {
                throw new com.mail.common.BizException(50002,
                        MailErrors.explain(e, "IMAP " + account.getHost() + ":"
                                + (account.getPort() != null ? account.getPort() : 993)), e);
            }
        } finally {
            closeQuietly(store);
        }
        return n[0];
    }

    @Override
    public int fetchSentInto(MailAccount account, int sinceSeq,
                             java.util.function.Consumer<Message> sink) {
        int[] n = {0};
        Store store = null;
        try {
            store = openStore(account);
            String sentName = findSentFolderName(store);
            if (sentName == null) {
                log.info("账户 {} 未发现已发送箱，跳过已发送同步", account.getEmail());
                return 0;
            }
            final String name = sentName;
            withWatchdog(store, (s, beat) -> {
                fetchWindow(s.getFolder(name), account, sinceSeq, beat, m -> { sink.accept(m); n[0]++; });
                return null;
            });
        } catch (Exception e) {
            if (n[0] == 0) {
                throw new com.mail.common.BizException(50003,
                        MailErrors.explain(e, "IMAP 已发送 " + account.getHost()), e);
            }
        } finally {
            closeQuietly(store);
        }
        return n[0];
    }

    /** 已连接 Store 上按常见命名解析服务器已发送文件夹名。找不到返回 null（跳过已发送同步）。 */
    private String findSentFolderName(Store store) {
        try {
            Folder[] all = store.getDefaultFolder().list("*");
            String fallback = null;
            for (Folder f : all) {
                String n = f.getFullName();
                String name = f.getName();
                if (n == null || name == null || "\\Noselect".equalsIgnoreCase(name)) continue;
                String lower = n.toLowerCase();
                if (looksLikeSent(lower) && fallback == null) fallback = n;
            }
            return fallback;
        } catch (Exception e) {
            log.debug("探测已发送箱失败: {}", e.getMessage());
            return null;
        }
    }

    private static boolean looksLikeSent(String lowerName) {
        if (lowerName.contains("draft") || lowerName.contains("trash")
                || lowerName.contains("deleted") || lowerName.contains("junk")
                || lowerName.contains("spam")) return false;
        return lowerName.contains("已发送")
                || lowerName.endsWith("/sent") || lowerName.equals("sent")
                || lowerName.contains("sent items") || lowerName.contains("sent messages");
    }

    /** 建立 IMAP 连接并上报 ID，返回已连接的 Store（调用方负责 closeQuietly）。 */
    private Store openStore(MailAccount account) throws Exception {
        Store store = Session.getInstance(propsOf(account)).getStore("imap");
        int resolvedPort = account.getPort() != null ? account.getPort()
                : ("SSL".equalsIgnoreCase(account.getEncryption()) ? 993 : 143);
        store.connect(account.getHost(), resolvedPort, account.getUsername(), password(account));
        sendImapId(store);
        return store;
    }

    private void closeQuietly(Store store) {
        if (store != null) {
            try { store.close(); } catch (Exception ignored) { }
        }
    }

    /** 已连接 Store 上的拉取任务，允许抛出受检异常；每次解析心跳可延长看门狗强关时刻。 */
    private interface StoreTask<T> { T apply(Store store, Heartbeat beat) throws Exception; }

    /**
     * 看门狗心跳：一个周期任务定期检查 deadline，若连续 {@link #FETCH_TIMEOUT_SEC} 秒
     * 没有 touch() 就强关 store 打断卡死的 socket 读。业务侧在关键节点（连上、打开、批量 fetch、
     * 每封邮件解析前）调用 touch()，把强关时钟推到「当前 + FETCH_TIMEOUT_SEC」。
     */
    static final class Heartbeat {
        private final java.util.concurrent.atomic.AtomicLong deadline;
        private final java.util.concurrent.ScheduledFuture<?> killer;
        Heartbeat(java.util.concurrent.atomic.AtomicLong d, java.util.concurrent.ScheduledFuture<?> k) {
            this.deadline = d;
            this.killer = k;
        }
        void touch() { deadline.set(System.currentTimeMillis() + FETCH_TIMEOUT_SEC * 1000L); }
        void stop() { killer.cancel(true); }
    }

    /**
     * 在 store 上运行拉取任务，用「心跳式空闲超时」看门狗在长时间无进展时强关连接。
     * 建连 + 首次 fetch 阶段没有中间心跳点，故 arm 时给到 2 倍 idle 预算。
     */
    private <T> T withWatchdog(Store store, StoreTask<T> task) throws Exception {
        java.util.concurrent.atomic.AtomicLong deadline =
                new java.util.concurrent.atomic.AtomicLong(
                        System.currentTimeMillis() + 2L * FETCH_TIMEOUT_SEC * 1000L);
        java.util.concurrent.ScheduledFuture<?> killer = WATCHDOG.scheduleAtFixedRate(() -> {
            if (System.currentTimeMillis() > deadline.get()) {
                try { store.close(); } catch (Exception ignored) { }
            }
        }, 10, 10, java.util.concurrent.TimeUnit.SECONDS);
        Heartbeat beat = new Heartbeat(deadline, killer);
        try {
            return task.apply(store, beat);
        } finally {
            beat.stop();
        }
    }

    /** 按序号窗口打开某远端文件夹并逐封解析为本地 Message（folderId 由 AccountSync 落库时再定）。 */
    private List<Message> fetchWindow(Folder folder, MailAccount account, int sinceSeq, Heartbeat beat) throws Exception {
        List<Message> result = new ArrayList<>();
        fetchWindow(folder, account, sinceSeq, beat, result::add);
        return result;
    }

    /**
     * 流式逐封解析：每解析出一封立刻回调 {@code sink}（由 AccountSync 即时落库），
     * 不在内存里攒整箱列表。这样即使中途被看门狗/网络打断，已落库的邮件也不丢。
     */
    private void fetchWindow(Folder folder, MailAccount account, int sinceSeq, Heartbeat beat,
                             java.util.function.Consumer<Message> sink) throws Exception {
        // 使用 READ_WRITE 以发送 SELECT 而非 EXAMINE：部分 IMAP 服务器（如 master336.cn）
        // 不支持 RFC 3501 的 EXAMINE 命令，会返回 BAD Command not recognized。
        folder.open(Folder.READ_WRITE);
        try {
            int total = folder.getMessageCount();
            if (total == 0) return;
            int windowFrom = Math.max(1, total - RECENT_WINDOW + 1);
            int start;
            if (sinceSeq <= 0) {
                // 首次全量（重置后清空 / 无本地高水位）：拉取整箱所有邮件，
                // 不受 RECENT_WINDOW 限制，否则历史邮件永远取不全。
                start = 1;
            } else if (total > sinceSeq) {
                // 增量：只取高水位之后的新邮件；窗口下限兜底防止水位异常倒退时全箱重扫。
                start = Math.max(windowFrom, sinceSeq + 1);
            } else if (total == sinceSeq) {
                return;
            } else {
                // 服务器总数少于本地水位（有删除/序号重排）：回退窗口内重扫。
                start = windowFrom;
            }
            if (start > total) return;
            jakarta.mail.Message[] messages = folder.getMessages(start, total);
            // 预取信封/内容信息，减少逐条头部往返
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add(FetchProfile.Item.CONTENT_INFO);
            fp.add(FetchProfile.Item.FLAGS);
            folder.fetch(messages, fp);
            // 从旧往新解析并即时落库：中途被看门狗/断连打断时，已入库的是「连续前缀」，
            // 下一轮以 maxRemoteSeq 水位即可从断点续拉；若反过来从新往旧拉，断连会在水位处留空洞，
            // 使更老的邮件被永久跳过。
            for (int i = 0; i < messages.length; i++) {
                // 每封邮件解析前延长空闲预算：慢网关逐封 BODY[] 拉取可能各耗数十秒，
                // 但只要还在推进就不应被强关（旧一次性墙钟会误杀整轮剩余的邮件）。
                if (beat != null) beat.touch();
                Message parsed = toEntity(account, messages[i], folderId(account));
                if (parsed != null) sink.accept(parsed);
            }
        } finally {
            if (folder.isOpen()) folder.close(false);
        }
    }

    private long folderId(MailAccount account) {
        // v1：收件箱固定为账户首个文件夹，后续由 SyncWorker 按名称映射
        return 1L;
    }

    private Message toEntity(MailAccount account, jakarta.mail.Message m, long folderId) {
        Message msg = new Message();
        msg.setUserId(account.getUserId());
        msg.setAccountId(account.getId());
        msg.setFolderId(folderId);
        boolean parseError = false;
        try {
            // 先转存原始 MIME（仅调试开关打开时）：即使后续解析异常，也留下可反复解析的 .eml
            saveRawMime(account, m);
            msg.setSubject(MailHeaderDecoder.decodeSubject(m));
            msg.setSender(decodeSender(m));
            msg.setSenderEmail(firstAddress(m));
            msg.setRecipients(joinAddresses(m, RecipientType.TO));
            msg.setCc(joinAddresses(m, RecipientType.CC));
            java.util.Date d = m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate();
            msg.setDate(d == null ? LocalDateTime.now() : LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault()));
            MailContentParser.ParsedBody content = parseContent(m);
            msg.setBodyHtml(content.html);
            msg.setBodyText(content.text);
            // 携带同步阶段解析到的内嵌图片，供 AccountSync 在落库后持久化
            msg.getPendingInline().addAll(content.inline);
            // 普通附件（含 message/rfc822 邮件副本 .eml）：同样交给 AccountSync 落库
            msg.getPendingAttachments().addAll(content.attachments);
            if (!content.attachments.isEmpty()) msg.setHasAttach(true);
            msg.setSize((long) m.getSize());
            msg.setRemoteUid(String.valueOf(m.getMessageNumber()));
            msg.setFlags(0);
        } catch (Exception e) {
            parseError = true;
            log.warn("解析邮件失败 uid={}: {} ({})", m.getMessageNumber(), e.getMessage(),
                    e.getClass().getSimpleName());
            log.debug("解析邮件失败详情", e);
        }
        // 兜底判定：主题、正文、附件全空且不是解析异常——几乎只可能是连接被中途强关
        // （旧墙钟看门狗误杀的典型产物），此时不入库，留给下一轮重新拉取，避免留下
        // 永久「空邮件」。真正确有空正文的邮件极少，且主题通常仍在。
        if (!parseError && isUnparsedEmpty(msg)) {
            log.info("跳过疑似解析中断的空邮件 uid={} size={}（下轮重取）",
                    msg.getRemoteUid(), msg.getSize());
            return null;
        }
        return parseError ? null : msg;
    }

    /** 主题、正文（html+text）全空视为未被成功解析。 */
    private static boolean isUnparsedEmpty(Message msg) {
        boolean subjBlank = msg.getSubject() == null || msg.getSubject().isBlank();
        boolean htmlBlank = msg.getBodyHtml() == null || msg.getBodyHtml().isBlank();
        boolean textBlank = msg.getBodyText() == null || msg.getBodyText().isBlank();
        return subjBlank && htmlBlank && textBlank;
    }

    /**
     * 解析正文。优先用 getContent()（完整内容），失败时回退到原始 RFC822 流。
     * 部分 IMAP 服务器（如 master336.cn 这类老/内网服务器）无法返回 BODYSTRUCTURE，
     * 此时 getInputStream() 会抛 "Unable to load BODYSTRUCTURE"；改用 IMAPMessage.getMimeStream()
     * 直接以 BODY[] 拉整封原始 MIME，再交给 Jakarta Mail 离线解析。
     */
    private MailContentParser.ParsedBody parseContent(jakarta.mail.Message m) {
        MailContentParser.ParsedBody parsed = new MailContentParser.ParsedBody();
        boolean needFallback = false;
        try {
            Object raw = m.getContent();
            parsed = MailContentParser.parse(raw);
            // getContent() 成功但解析出空正文：多半是 Content-Type 未被识别、
            // 或服务器不返回 BODYSTRUCTURE 导致逐 part 读取失败（附件也会随之丢失），走原始 MIME 回退
            if (isBlankBody(parsed)) {
                needFallback = true;
                log.info("getContent 返回空正文 type={} 类={}，尝试原始 MIME 回退 (uid={})",
                        safeType(m), raw == null ? "null" : raw.getClass().getSimpleName(), m.getMessageNumber());
            }
        } catch (Exception e1) {
            log.debug("getContent() 失败: {}；改用原始 MIME 流回退", e1.getMessage());
            needFallback = true;
        }
        if (needFallback) {
            try {
                MailContentParser.ParsedBody fb = parseRawMessage(m);
                mergeFallback(parsed, fb);
            } catch (Exception ignore) { }
        }
        return parsed;
    }

    /**
     * 把原始 MIME 回退解析出的内容并入主结果：正文仅在主结果缺失时采用回退；
     * 附件与内联取并集。关键是不因回退正文为空而整体丢弃——附件往往正是靠回退才读得到。
     */
    static void mergeFallback(MailContentParser.ParsedBody target, MailContentParser.ParsedBody fb) {
        if (fb == null) return;
        if (isBlankBody(target) && !isBlankBody(fb)) {
            target.html = fb.html;
            target.text = fb.text;
        }
        target.attachments.addAll(fb.attachments);
        target.inline.addAll(fb.inline);
    }

    private static boolean isBlankBody(MailContentParser.ParsedBody b) {
        return (b.html == null || b.html.isBlank()) && (b.text == null || b.text.isBlank());
    }

    private static String safeType(jakarta.mail.Message m) {
        try { return m.getContentType(); } catch (Exception e) { return "?"; }
    }

    /**
     * 获取整封邮件的原始 RFC822 字节。依次尝试：Angus/Eclipse IMAP 的 getMimeStream()
     * （直接 BODY[]）→ Message.writeTo()（可绕过 BODYSTRUCTURE）→ getInputStream()。
     * 全部失败返回 null。既供原始 MIME 回退解析，也供转存调试文件。
     */
    byte[] rawMimeBytes(jakarta.mail.Message m) {
        byte[] rawBytes = tryInvoke(m, "getMimeStream");
        if (rawBytes == null || rawBytes.length == 0) {
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                m.writeTo(bos);
                byte[] b = bos.toByteArray();
                if (b != null && b.length > 0) rawBytes = b;
            } catch (Exception ignore) { }
        }
        if (rawBytes == null || rawBytes.length == 0) {
            try (InputStream in = m.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                rawBytes = bos.toByteArray();
            } catch (Exception ignore) { }
        }
        return rawBytes;
    }

    /**
     * 调试用：把原始 MIME 流转存到 {rawDir}/{accountId}/{uid}-{date}.eml，供离线反复解析。
     * 任何异常都吞掉——转存失败绝不能影响正常收信。
     */
    private void saveRawMime(MailAccount account, jakarta.mail.Message m) {
        if (!appProperties.isSaveRawMime()) return;
        try {
            byte[] raw = rawMimeBytes(m);
            if (raw == null || raw.length == 0) return;
            java.nio.file.Path dir = java.nio.file.Paths.get(appProperties.getRawDir(),
                    String.valueOf(account.getId()));
            java.nio.file.Files.createDirectories(dir);
            String date = "";
            try {
                java.util.Date d = m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate();
                if (d != null) date = "-" + d.getTime();
            } catch (Exception ignore) { }
            java.nio.file.Path file = dir.resolve("msg-" + m.getMessageNumber() + date + ".eml");
            java.nio.file.Files.write(file, raw,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            log.info("已转存原始 MIME：{}", file.toAbsolutePath());
        } catch (Exception e) {
            log.debug("转存原始 MIME 失败 uid={}: {}", safeNumber(m), e.getMessage());
        }
    }

    private static String safeNumber(jakarta.mail.Message m) {
        try { return String.valueOf(m.getMessageNumber()); } catch (Exception e) { return "?"; }
    }

    /**
     * 从原始 RFC822 字节流重建 MimeMessage 并解析正文。
     */
    private MailContentParser.ParsedBody parseRawMessage(jakarta.mail.Message m) throws Exception {
        byte[] rawBytes = rawMimeBytes(m);
        if (rawBytes == null || rawBytes.length == 0) {
            throw new IllegalStateException("无法获取邮件原始 MIME");
        }
        jakarta.mail.Session session = jakarta.mail.Session.getInstance(new java.util.Properties());
        jakarta.mail.internet.MimeMessage parsed = new jakarta.mail.internet.MimeMessage(session,
                new java.io.ByteArrayInputStream(rawBytes));
        // 若离线 MimeMessage 仍解析失败，退化为按纯文本处理整段 body
        try {
            return MailContentParser.parse(parsed.getContent());
        } catch (Exception e) {
            log.debug("离线解析 getContent 仍失败: {}；按纯文本回退", e.getMessage());
            return parseAsPlainText(parsed);
        }
    }

    /** 反射调用无参返回 InputStream 的方法（如 getMimeStream），失败返回 null。 */
    private byte[] tryInvoke(jakarta.mail.Message m, String methodName) {
        try {
            java.lang.reflect.Method method = m.getClass().getMethod(methodName);
            Object result = method.invoke(m);
            if (result instanceof InputStream) {
                try (InputStream in = (InputStream) result) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
                    return bos.toByteArray();
                }
            }
        } catch (Exception ignore) { }
        return null;
    }

    /** 从 MimeMessage 的原始输入流中，剖开头/空行后的正文，作为纯文本兜底。 */
    private MailContentParser.ParsedBody parseAsPlainText(jakarta.mail.internet.MimeMessage parsed) {
        MailContentParser.ParsedBody body = new MailContentParser.ParsedBody();
        try (InputStream in = parsed.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            byte[] all = bos.toByteArray();
            // 跳过头部（第一个空行）
            int headerEnd = -1;
            for (int i = 0; i < all.length - 3; i++) {
                if (all[i] == '\r' && all[i + 1] == '\n' && all[i + 2] == '\r' && all[i + 3] == '\n') {
                    headerEnd = i + 4;
                    break;
                }
                if (all[i] == '\n' && all[i + 1] == '\n') {
                    headerEnd = i + 2;
                    break;
                }
            }
            byte[] bodyBytes = headerEnd < 0 ? all
                    : java.util.Arrays.copyOfRange(all, headerEnd, all.length);
            // 优先 UTF-8，失败回退 GB18030
            String text;
            try {
                text = new String(bodyBytes, StandardCharsets.UTF_8);
                if (text.indexOf('\uFFFD') >= 0) throw new Exception("utf8 失败");
            } catch (Exception e) {
                text = new String(bodyBytes, java.nio.charset.Charset.forName("GB18030"));
            }
            body.text = text;
            body.html = "<pre style=\"white-space:pre-wrap\">" + escapeHtml(text) + "</pre>";
        } catch (Exception ignore) { }
        return body;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * 解析地址列表。部分 IMAP 服务器（如 gientech）在构造 ENVELOPE 时，会把带引号、
     * 内含逗号的显示名 `"Zhang,Dong" <a@x>` 按逗号错误拆分，导致 getFrom()/getRecipients()
     * 返回残片（地址变成 `"Zhang`）。而 getHeader(...) 取回的是原样（unfold 后）的信头行，
     * InternetAddress.parse 能按 RFC 822 正确识别引号内的逗号。故优先用信头原文，取不到再退回信封。
     */
    private InternetAddress[] addresses(jakarta.mail.Message m, String header,
                                        RecipientType type) {
        try {
            String[] hv = m.getHeader(header);
            if (hv != null && hv.length > 0) {
                String raw = String.join(" ", hv).trim();
                if (!raw.isEmpty()) {
                    InternetAddress[] parsed = InternetAddress.parse(raw, false);
                    if (parsed.length > 0) return parsed;
                }
            }
        } catch (Exception e) {
            log.debug("按信头 {} 解析地址失败，退回信封: {}", header, e.getMessage());
        }
        try {
            var addrs = header.equalsIgnoreCase("From") ? m.getFrom() : m.getRecipients(type);
            if (addrs == null) return new InternetAddress[0];
            java.util.List<InternetAddress> out = new java.util.ArrayList<>();
            for (var a : addrs) if (a instanceof InternetAddress ia) out.add(ia);
            return out.toArray(new InternetAddress[0]);
        } catch (Exception e) {
            return new InternetAddress[0];
        }
    }

    private String decodeSender(jakarta.mail.Message m) {
        try {
            InternetAddress[] from = addresses(m, "From", RecipientType.TO);
            if (from.length == 0) return "";
            InternetAddress ia = from[0];
            String personal = ia.getPersonal();
            if (personal != null && !personal.isBlank()) {
                // personal 已被 InternetAddress 用 RFC 2047 解码一次；再走宽松修复
                return MailHeaderDecoder.decodeText(personal);
            }
            String address = ia.getAddress();
            return address == null ? "" : address;
        } catch (Exception e) {
            return "";
        }
    }

    /** 发件人纯地址：优先信头原文解析出的第一个地址。 */
    private String firstAddress(jakarta.mail.Message m) {
        try {
            InternetAddress[] from = addresses(m, "From", RecipientType.TO);
            if (from.length == 0) return "";
            String addr = from[0].getAddress();
            return addr == null ? "" : addr;
        } catch (Exception e) {
            return "";
        }
    }

    private String joinAddresses(jakarta.mail.Message m, RecipientType type) {
        String header = type == RecipientType.CC ? "Cc" : type == RecipientType.BCC ? "Bcc" : "To";
        InternetAddress[] addrs = addresses(m, header, type);
        StringBuilder sb = new StringBuilder();
        for (InternetAddress a : addrs) {
            if (sb.length() > 0) sb.append(",");
            sb.append(a.toString());
        }
        return sb.toString();
    }
}