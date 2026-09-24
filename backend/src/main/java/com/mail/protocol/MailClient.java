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

    /**
     * 增量拉取：仅取回序号大于 {@code sinceSeq} 的新邮件，降低稳态开销。
     * {@code sinceSeq} 为本地已存的最高 IMAP 消息序号（0 表示全量）。默认实现忽略该
     * 参数退回全量拉取——POP3 的消息编号在下载/删除后会重排，不适用序号增量。
     */
    default List<Message> fetchInbox(MailAccount account, int sinceSeq) {
        return fetchInbox(account);
    }
}