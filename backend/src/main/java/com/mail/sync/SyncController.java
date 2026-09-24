package com.mail.sync;

import com.mail.account.domain.MailAccount;
import com.mail.account.domain.MailAccountRepository;
import com.mail.auth.CurrentUser;
import com.mail.common.BizException;
import com.mail.common.ApiResponse;
import com.mail.task.TaskService;
import com.mail.task.domain.Task;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 手动触发同步：改为后台异步任务，立即返回任务 ID。
 */
@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncController {

    private final MailAccountRepository accountRepository;
    private final TaskService taskService;

    @PostMapping
    public ApiResponse<Map<String, Object>> trigger(@RequestParam Long accountId) {
        Long userId = CurrentUser.requireId();
        MailAccount acc = accountRepository.findById(accountId).orElseThrow(() -> new BizException("账户不存在"));
        if (!acc.getUserId().equals(userId)) throw new BizException(40300, "无权操作");
        Task task = taskService.submit(userId, "同步账户 " + acc.getEmail(),
                com.mail.task.AccountSyncTaskHandler.TYPE, accountId);
        return ApiResponse.ok(Map.of("taskId", task.getId()));
    }

    /**
     * 立即收取全部启用账户的新邮件（邮件管理的「刷新」按钮）：为每个账户提交一个后台任务。
     */
    @PostMapping("/all")
    public ApiResponse<Map<String, Object>> triggerAll() {
        Long userId = CurrentUser.requireId();
        List<MailAccount> accounts = accountRepository.findByUserIdAndActive(userId, true);
        List<Long> taskIds = accounts.stream()
                .map(a -> taskService.submit(userId, "同步账户 " + a.getEmail(),
                        com.mail.task.AccountSyncTaskHandler.TYPE, a.getId()).getId())
                .collect(Collectors.toList());
        return ApiResponse.ok(Map.of("taskIds", taskIds, "count", taskIds.size()));
    }
}