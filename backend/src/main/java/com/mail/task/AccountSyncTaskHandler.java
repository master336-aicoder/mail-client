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
        if (!acc.getUserId().equals(task.getUserId())) throw new BizException(40300, "无权操作");
        // 进度与说明由 AccountSync 按真实阶段上报
        accountSync.sync(acc, progress::report);
    }
}
