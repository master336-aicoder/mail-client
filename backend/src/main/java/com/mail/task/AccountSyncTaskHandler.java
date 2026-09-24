package com.mail.task;

import com.mail.account.domain.MailAccount;
import com.mail.account.domain.MailAccountRepository;
import com.mail.common.BizException;
import com.mail.sync.AccountSync;
import com.mail.task.domain.Task;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 账户同步任务处理器。
 */
@Component
@RequiredArgsConstructor
public class AccountSyncTaskHandler implements TaskHandler {

    public static final String TYPE = "account_sync";

    private final MailAccountRepository accountRepository;
    private final AccountSync accountSync;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String name() {
        return "账户同步";
    }

    @Override
    public void run(Task task, Progress progress) throws Exception {
        Long accountId = task.getAccountId();
        if (accountId == null) throw new BizException("缺少账户信息，无法同步");
        MailAccount acc = accountRepository.findById(accountId)
                .orElseThrow(() -> new BizException("账户不存在"));
        progress.report(5, "开始连接 " + acc.getEmail());
        // 拉取数据阶段（fetchInbox 内完成）
        progress.report(30, "正在拉取收件箱邮件…");
        accountSync.sync(acc);
        progress.report(80, "同步完成，正在应用规则与推送…");
        progress.report(100, "完成");
    }
}
