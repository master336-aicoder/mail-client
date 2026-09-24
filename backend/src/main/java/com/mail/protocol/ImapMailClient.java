package com.mail.protocol;

import com.mail.account.domain.MailAccount;
import com.mail.common.CryptoService;
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
import jakarta.mail.internet.MimeUtility;

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

    /**
     * 每次同步仅拉取最近若干封：避免全量遍历整箱（大箱子下会长时间阻塞、超时）。
     * 60s 轮询场景下，最近窗口足够覆盖新邮件；历史邮件由首屏之外的其它机制处理。
     */
    private static final int RECENT_WINDOW = 50;

    /** 单次 IMAP 拉取的墙钟兜底超时（秒），到点强制关闭连接以打断卡死的 socket 读。 */
    private static final int FETCH_TIMEOUT_SEC = 45;

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
        int port = account.getPort() != null ? account.getPort() : 993;
        props.put("mail.store.protocol", "imap");
        props.put("mail.imap.host", host);
        props.put("mail.imap.port", String.valueOf(port));
        String enc = account.getEncryption() == null ? "SSL" : account.getEncryption();
        if ("SSL".equalsIgnoreCase(enc)) {
            props.put("mail.imap.ssl.enable", "true");
        } else if ("STARTTLS".equalsIgnoreCase(enc)) {
            props.put("mail.imap.starttls.enable", "true");
        }
        // 关键：禁用 partial fetch 并提高 fetchsize，确保 getContent() 返回完整的
        // String/Multipart 而非分段的 InputStream（否则正文解析会得到 null）
        props.put("mail.imap.partialfetch", "false");
        props.put("mail.imap.fetchsize", "1048576");
        // 显式设置连接/读/写超时：避免在慢速或异常消息上无限挂起，保证单次同步总能完成
        props.put("mail.imap.connectiontimeout", "15000");
        props.put("mail.imap.timeout", "30000");
        props.put("mail.imap.writetimeout", "15000");
        return props;
    }

    private String password(MailAccount account) {
        return cryptoService.decrypt(account.getSecretEncrypted());
    }

    @Override
    public boolean testConnection(MailAccount account) {
        Properties props = propsOf(account);
        try (Store store = Session.getInstance(props).getStore("imap")) {
            store.connect(account.getHost(), account.getPort(), account.getUsername(), password(account));
            return true;
        } catch (Exception e) {
            log.warn("IMAP 连接失败 {}: {}", account.getEmail(), e.getMessage());
            return false;
        }
    }

    @Override
    public List<Message> fetchInbox(MailAccount account) {
        List<Message> result = new ArrayList<>();
        Properties props = propsOf(account);
        Store store = null;
        try {
            store = Session.getInstance(props).getStore("imap");
            store.connect(account.getHost(), account.getPort(), account.getUsername(), password(account));
            final Store watchdogTarget = store;
            // 兜底看门狗：部分企业网关会在 BODY FETCH 时长时间挂起且不遵守 SO_TIMEOUT，
            // 到点后强制 close(store) 以打断阻塞的 socket 读，避免拖垮唯一的调度线程。
            java.util.concurrent.ScheduledFuture<?> killer = WATCHDOG.schedule(() -> {
                try { watchdogTarget.close(); } catch (Exception ignored) { }
            }, FETCH_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS);
            try {
                Folder inbox = store.getFolder("INBOX");
                inbox.open(Folder.READ_ONLY);
                int total = inbox.getMessageCount();
                int from = Math.max(1, total - RECENT_WINDOW + 1);
                jakarta.mail.Message[] messages = inbox.getMessages(from, total);
                // 预取信封/内容信息，减少逐条头部往返
                FetchProfile fp = new FetchProfile();
                fp.add(FetchProfile.Item.ENVELOPE);
                fp.add(FetchProfile.Item.CONTENT_INFO);
                fp.add(FetchProfile.Item.FLAGS);
                inbox.fetch(messages, fp);
                // 从最新往旧解析：即使中途遇到某封异常邮件被看门狗截断，最新邮件也已优先入库
                for (int i = messages.length - 1; i >= 0; i--) {
                    result.add(toEntity(account, messages[i], folderId(account)));
                }
                if (inbox.isOpen()) inbox.close(false);
            } finally {
                killer.cancel(true);
            }
        } catch (Exception e) {
            log.warn("IMAP 拉取失败 {}: {}", account.getEmail(), e.getMessage());
        } finally {
            if (store != null) {
                try { store.close(); } catch (Exception ignored) { }
            }
        }
        return result;
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
        try {
            msg.setSubject(m.getSubject());
            msg.setSender(decodeSender(m));
            msg.setSenderEmail(m.getFrom() != null && m.getFrom().length > 0 ? ((InternetAddress) m.getFrom()[0]).getAddress() : "");
            msg.setRecipients(joinAddresses(m, RecipientType.TO));
            msg.setCc(joinAddresses(m, RecipientType.CC));
            java.util.Date d = m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate();
            msg.setDate(d == null ? LocalDateTime.now() : LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault()));
            MailContentParser.ParsedBody content = parseContent(m);
            msg.setBodyHtml(content.html);
            msg.setBodyText(content.text);
            // 携带同步阶段解析到的内嵌图片，供 AccountSync 在落库后持久化
            msg.getPendingInline().addAll(content.inline);
            msg.setSize((long) m.getSize());
            msg.setRemoteUid(String.valueOf(m.getMessageNumber()));
            msg.setFlags(0);
        } catch (Exception e) {
            log.warn("解析邮件失败 uid={}: {} ({})", m.getMessageNumber(), e.getMessage(),
                    e.getClass().getSimpleName());
            log.debug("解析邮件失败详情", e);
        }
        return msg;
    }

    /**
     * 解析正文。优先用 getContent()（完整内容），失败时回退到读取原始数据流，
     * 尽力从原始字节中提取可读正文（部分服务器对个别消息无法返回可解析的 BODYSTRUCTURE）。
     */
    private MailContentParser.ParsedBody parseContent(jakarta.mail.Message m) {
        try {
            return MailContentParser.parse(m.getContent());
        } catch (Exception ignored) {
            // getContent() 失败（如 Unable to load BODYSTRUCTURE）时，退而求其次读取原始流
            try {
                return MailContentParser.parse(readRawStream(m));
            } catch (Exception e2) {
                return new MailContentParser.ParsedBody();
            }
        }
    }

    private String readRawStream(jakarta.mail.Message m) throws Exception {
        try (InputStream in = m.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String decodeSender(jakarta.mail.Message m) {
        try {
            if (m.getFrom() == null || m.getFrom().length == 0) return "";
            InternetAddress ia = (InternetAddress) m.getFrom()[0];
            String personal = ia.getPersonal();
            if (personal != null && !personal.isBlank()) {
                // 解码 RFC 2047 编码词（如 =?utf-8?q?...?=）
                return MimeUtility.decodeText(personal);
            }
            String address = ia.getAddress();
            return address == null ? "" : address;
        } catch (Exception e) {
            try {
                if (m.getFrom() != null && m.getFrom().length > 0) {
                    return MimeUtility.decodeText(m.getFrom()[0].toString());
                }
            } catch (Exception ignored) {
                // 忽略
            }
            return "";
        }
    }

    private String joinAddresses(jakarta.mail.Message m, RecipientType type) {
        try {
            var addrs = m.getRecipients(type);
            if (addrs == null) return "";
            StringBuilder sb = new StringBuilder();
            for (var a : addrs) {
                if (sb.length() > 0) sb.append(",");
                sb.append(a.toString());
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}