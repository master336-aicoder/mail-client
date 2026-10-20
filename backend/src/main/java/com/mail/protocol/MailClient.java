package com.mail.protocol;

import com.mail.account.domain.MailAccount;
import com.mail.mail.domain.Message;

import java.util.List;

/**
 * 收件协议通用接口（IMAP4 / POP3）。
 */
public interface MailClient {

    /**
     * 登录并测试连接。成功返回 {@code null}，失败返回可直接展示的原因。
     * 使用账户中已加密存储的密码。
     */
    default String testConnection(MailAccount account) {
        return testConnection(account, null);
    }

    /**
     * 同上，但允许指定本次登录使用的口令。
     * {@code password} 为 null 表示沿用账户已存的密文；表单里尚未保存的配置测试时走这条分支，
     * 否则会拿不到用户刚输入的密码（历史缺陷：测试连接总按空密码登录，报「认证失败」）。
     */
    String testConnection(MailAccount account, String password);

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

    /**
     * 拉取服务器「已发送」箱邮件。默认实现返回空——POP3 协议只暴露 INBOX，没有已发送箱；
     * IMAP 实现会探测服务器已发送文件夹并按窗口增量拉取。{@code sinceSeq} 同 {@link #fetchInbox}。
     */
    default List<Message> fetchSent(MailAccount account, int sinceSeq) {
        return java.util.Collections.emptyList();
    }

    /**
     * 流式收件：每解析出一封立刻交给 {@code sink}（供 AccountSync 即时落库），
     * 返回成功解析并回调的封数。默认实现退回「整箱缓冲后逐封回调」，
     * POP3 等无需真流式的协议直接复用即可；IMAP 覆盖为边拉边回调，抗中途断连。
     */
    default int fetchInboxInto(MailAccount account, int sinceSeq,
                               java.util.function.Consumer<Message> sink) {
        int n = 0;
        for (Message m : fetchInbox(account, sinceSeq)) { sink.accept(m); n++; }
        return n;
    }

    /** 流式拉取已发送箱，语义同 {@link #fetchInboxInto}。 */
    default int fetchSentInto(MailAccount account, int sinceSeq,
                              java.util.function.Consumer<Message> sink) {
        int n = 0;
        for (Message m : fetchSent(account, sinceSeq)) { sink.accept(m); n++; }
        return n;
    }
}