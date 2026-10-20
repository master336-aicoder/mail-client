package com.mail.protocol;

import com.mail.account.domain.MailAccount;
import com.mail.common.CryptoService;
import com.mail.common.MailErrors;
import com.mail.mail.domain.Message;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.mail.Folder;
import jakarta.mail.Session;
import jakarta.mail.Store;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * POP3 客户端。POP3 拉取后不保留标记，简化实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Pop3MailClient implements MailClient {

    private final CryptoService cryptoService;

    @Override
    public String testConnection(MailAccount account, String plainPassword) {
        Properties props = new Properties();
        int port = account.getPort() != null ? account.getPort() : 995;
        props.put("mail.pop3.host", account.getHost());
        props.put("mail.pop3.port", String.valueOf(port));
        TlsProps.apply(props, "mail.pop3", account.getEncryption(), account.isIgnoreCertError());
        String secret = plainPassword != null && !plainPassword.isEmpty()
                ? plainPassword : cryptoService.decrypt(account.getSecretEncrypted());
        try (Store store = Session.getInstance(props).getStore("pop3")) {
            store.connect(account.getHost(), port, account.getUsername(), secret);
            return null;
        } catch (Exception e) {
            log.warn("POP3 连接失败 {}: {}", account.getEmail(), e.getMessage());
            return MailErrors.explain(e, "POP3 " + account.getHost() + ":" + port);
        }
    }

    /** POP3 每次拉取的窗口大小：只看最新 N 封，避免万封大箱全量拉取耗尽时间和带宽。 */
    private static final int FETCH_WINDOW = Integer.getInteger("mail.pop3.fetchWindow", 200);
    /** 单次 POP3 拉取的挂钟预算：超时即截断，剩余邮件留到下一轮。 */
    private static final long FETCH_BUDGET_MS = Long.getLong("mail.pop3.fetchBudgetMs", 120_000L);

    @Override
    public List<Message> fetchInbox(MailAccount account) {
        List<Message> result = new ArrayList<>();
        int port = account.getPort() != null ? account.getPort() : 995;
        Properties props = new Properties();
        props.put("mail.pop3.host", account.getHost());
        props.put("mail.pop3.port", String.valueOf(port));
        TlsProps.apply(props, "mail.pop3", account.getEncryption(), account.isIgnoreCertError());
        try (Store store = Session.getInstance(props).getStore("pop3")) {
            store.connect(account.getUsername(), cryptoService.decrypt(account.getSecretEncrypted()));
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            int total = inbox.getMessageCount();
            // 只处理最新窗口；total<=0 时 start>end，getMessages 返回空数组
            int start = Math.max(1, total - FETCH_WINDOW + 1);
            if (total > FETCH_WINDOW) {
                log.info("POP3 {} 共 {} 封，仅拉取最新 {} 封（第 {}-{} 封）",
                        account.getEmail(), total, FETCH_WINDOW, start, total);
            }
            jakarta.mail.Message[] window = total <= 0 ? new jakarta.mail.Message[0]
                    : inbox.getMessages(start, total);
            long deadline = System.currentTimeMillis() + FETCH_BUDGET_MS;
            for (var m : window) {
                if (System.currentTimeMillis() > deadline) {
                    log.warn("POP3 {} 拉取超过 {}ms 预算，本轮截断（已解析 {} 封）",
                            account.getEmail(), FETCH_BUDGET_MS, result.size());
                    break;
                }
                Message msg = new Message();
                msg.setUserId(account.getUserId());
                msg.setAccountId(account.getId());
                msg.setFolderId(1L);
                try {
                    msg.setSubject(MailHeaderDecoder.decodeSubject(m));
                    msg.setSender(m.getFrom() != null && m.getFrom().length > 0 ? m.getFrom()[0].toString() : "");
                    msg.setSenderEmail(m.getFrom() != null && m.getFrom().length > 0
                            ? ((jakarta.mail.internet.InternetAddress) m.getFrom()[0]).getAddress() : "");
                    msg.setDate(LocalDateTime.now());
                    java.util.Date d = m.getReceivedDate();
                    if (d != null) msg.setDate(LocalDateTime.ofInstant(d.toInstant(), java.time.ZoneId.systemDefault()));
                    MailContentParser.ParsedBody content = MailContentParser.parse(m.getContent());
                    msg.setBodyHtml(content.html);
                    msg.setBodyText(content.text);
                    // 内嵌图片与普通附件（含 .eml 邮件副本）交给 AccountSync 落库
                    msg.getPendingInline().addAll(content.inline);
                    msg.getPendingAttachments().addAll(content.attachments);
                    if (!content.attachments.isEmpty()) msg.setHasAttach(true);
                    msg.setSize((long) m.getSize());
                    msg.setRemoteUid(String.valueOf(m.getMessageNumber()));
                } catch (Exception e) {
                    // 单封解析失败只跳过这一封：入库半封空邮件比缺一封更糟
                    log.warn("POP3 解析失败 第 {} 封: {}", m.getMessageNumber(), e.getMessage());
                    continue;
                }
                result.add(msg);
            }
        } catch (Exception e) {
            log.warn("POP3 拉取失败 {}: {}", account.getEmail(), e.getMessage());
            if (result.isEmpty()) {
                throw new com.mail.common.BizException(50002,
                        MailErrors.explain(e, "POP3 " + account.getHost() + ":" + port), e);
            }
        }
        return result;
    }
}