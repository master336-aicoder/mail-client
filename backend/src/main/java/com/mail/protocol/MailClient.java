package com.mail.protocol;

import com.mail.account.domain.MailAccount;
import com.mail.mail.domain.Message;

import java.util.List;

/**
 * 收件协议通用接口（IMAP4 / POP3）。
 */
public interface MailClient {

    /**
     * 登录并测试连接。
     */
    boolean testConnection(MailAccount account);

    /**
     * 拉取收件箱新邮件，同步到本地并返回新增消息。
     */
    List<Message> fetchInbox(MailAccount account);
}