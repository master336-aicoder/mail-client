package com.mail.protocol;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 按账户协议返回对应收件客户端。
 */
@Component
@RequiredArgsConstructor
public class ProtocolFactory {

    private final ImapMailClient imap;
    private final Pop3MailClient pop3;

    public MailClient clientFor(String protocol) {
        if ("POP3".equalsIgnoreCase(protocol)) return pop3;
        return imap;
    }
}