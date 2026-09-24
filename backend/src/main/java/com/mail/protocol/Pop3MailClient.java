package com.mail.protocol;

import com.mail.account.domain.MailAccount;
import com.mail.common.CryptoService;
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
    public boolean testConnection(MailAccount account) {
        Properties props = new Properties();
        props.put("mail.pop3.host", account.getHost());
        props.put("mail.pop3.port", String.valueOf(account.getPort() != null ? account.getPort() : 995));
        if ("SSL".equalsIgnoreCase(account.getEncryption())) {
            props.put("mail.pop3.ssl.enable", "true");
        } else if ("STARTTLS".equalsIgnoreCase(account.getEncryption())) {
            props.put("mail.pop3.starttls.enable", "true");
        }
        try (Store store = Session.getInstance(props).getStore("pop3")) {
            store.connect(account.getHost(), account.getPort(), account.getUsername(),
                    cryptoService.decrypt(account.getSecretEncrypted()));
            return true;
        } catch (Exception e) {
            log.warn("POP3 连接失败 {}: {}", account.getEmail(), e.getMessage());
            return false;
        }
    }

    @Override
    public List<Message> fetchInbox(MailAccount account) {
        List<Message> result = new ArrayList<>();
        Properties props = new Properties();
        props.put("mail.pop3.host", account.getHost());
        props.put("mail.pop3.port", String.valueOf(account.getPort()));
        props.put("mail.pop3.ssl.enable", "true");
        try (Store store = Session.getInstance(props).getStore("pop3")) {
            store.connect(account.getUsername(), cryptoService.decrypt(account.getSecretEncrypted()));
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            for (var m : inbox.getMessages()) {
                Message msg = new Message();
                msg.setUserId(account.getUserId());
                msg.setAccountId(account.getId());
                msg.setFolderId(1L);
                try {
                    msg.setSubject(m.getSubject());
                    msg.setSender(m.getFrom() != null && m.getFrom().length > 0 ? m.getFrom()[0].toString() : "");
                    msg.setSenderEmail(m.getFrom() != null && m.getFrom().length > 0
                            ? ((jakarta.mail.internet.InternetAddress) m.getFrom()[0]).getAddress() : "");
                    msg.setDate(LocalDateTime.now());
                    java.util.Date d = m.getReceivedDate();
                    if (d != null) msg.setDate(LocalDateTime.ofInstant(d.toInstant(), java.time.ZoneId.systemDefault()));
                    MailContentParser.ParsedBody content = MailContentParser.parse(m.getContent());
                    msg.setBodyHtml(content.html);
                    msg.setBodyText(content.text);
                    msg.setSize((long) m.getSize());
                    msg.setRemoteUid(String.valueOf(m.getMessageNumber()));
                } catch (Exception e) {
                    log.warn("POP3 解析失败: {}", e.getMessage());
                }
                result.add(msg);
            }
        } catch (Exception e) {
            log.warn("POP3 拉取失败 {}: {}", account.getEmail(), e.getMessage());
        }
        return result;
    }
}