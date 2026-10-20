package com.mail.sync;

import com.mail.account.domain.MailAccount;
import com.mail.account.domain.MailAccountRepository;
import com.mail.auth.CurrentUser;
import com.mail.common.ApiResponse;
import com.mail.common.BizException;
import com.mail.task.TaskService;
import com.mail.task.domain.Task;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 手动触发收信：账户的周期同步任务已常驻在「后台任务」里，
 * 这里只是让某个账户立刻跑一轮，而不是新建一条任务记录。
 */
@Slf4j
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
        Task task = taskService.ensureAccountSyncTask(acc);
        taskService.runNow(userId, task.getId());
        return ApiResponse.ok(Map.of("taskId", task.getId()));
    }

    /**
     * 立即收取全部启用账户的新邮件（邮件管理的「刷新」按钮）。
     * 正在执行的账户跳过，不打断已在跑的那轮。
     */
    @PostMapping("/all")
    public ApiResponse<Map<String, Object>> triggerAll() {
        Long userId = CurrentUser.requireId();
        List<Long> taskIds = new ArrayList<>();
        for (MailAccount acc : accountRepository.findByUserIdAndActive(userId, true)) {
            try {
                Task task = taskService.ensureAccountSyncTask(acc);
                taskService.runNow(userId, task.getId());
                taskIds.add(task.getId());
            } catch (Exception e) {
                log.debug("账户 {} 立即收取跳过: {}", acc.getEmail(), e.getMessage());
            }
        }
        return ApiResponse.ok(Map.of("taskIds", taskIds, "count", taskIds.size()));
    }
}
