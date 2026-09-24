package com.mail.sync;

import com.mail.account.domain.MailAccount;
import com.mail.account.domain.MailAccountRepository;
import com.mail.mail.domain.Message;
import com.mail.mail.domain.MessageRepository;
import com.mail.protocol.MailClient;
import com.mail.protocol.ProtocolFactory;
import com.mail.push.PushService;
import com.mail.rule.RuleEngine;
import com.mail.rule.domain.Rule;
import com.mail.rule.domain.RuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 收件轮询同步：拉取 IMAP/POP3 新邮件 → 持久化 → 应用规则 → WebSocket 推送。
 * 轮询兜底（IMAP IDLE 作为可选增强）；生产可启用 IMAP IDLE 长连接。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncWorker {

    private final AccountSync accountSync;

    /**
     * 每 15s 检查一次：按各账户配置的收取频率仅同步已到期的启用账户。
     */
    @Scheduled(fixedDelay = 15_000)
    public void pollAll() {
        accountSync.syncEnabledAccountsDue();
    }
}