package com.mail.account;

import com.mail.account.domain.MailAccount;
import com.mail.account.dto.AccountRequest;
import com.mail.account.dto.AccountTestRequest;
import com.mail.account.dto.AccountUpdateRequest;
import com.mail.account.dto.AutoDetectRequest;
import com.mail.account.dto.ProbeResult;
import com.mail.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping("/auto-detect")
    public ApiResponse<List<ProbeResult>> autoDetect(@Valid @RequestBody AutoDetectRequest req) {
        return ApiResponse.ok(accountService.autoDetect(req.getEmail()));
    }

    @GetMapping
    public ApiResponse<List<MailAccount>> list() {
        return ApiResponse.ok(accountService.list());
    }

    @PostMapping
    public ApiResponse<MailAccount> create(@Valid @RequestBody AccountRequest req) {
        return ApiResponse.ok(accountService.create(req));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        accountService.delete(id);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}")
    public ApiResponse<MailAccount> update(@PathVariable Long id,
                                           @Valid @RequestBody AccountUpdateRequest req) {
        return ApiResponse.ok(accountService.update(id, req));
    }

    @PostMapping("/{id}/test")
    public ApiResponse<Map<String, Object>> test(@PathVariable Long id) {
        return ApiResponse.ok(accountService.test(id));
    }

    /**
     * 重置账户：清空该账户本地全部邮件与附件后重新拉取（只动本地，不删服务器邮件）。
     * 需先停用账户（停用会令其周期同步任务自动失效）方可重置，避免与后台轮询抢数据。
     * 删除在事务中提交后，再触发一次立即同步（手动触发不受停用影响，仍会重拉一次）。
     */
    @PostMapping("/{id}/reset")
    public ApiResponse<Map<String, Object>> reset(@PathVariable Long id) {
        Map<String, Object> result = accountService.reset(id);
        accountService.triggerResync(id);
        return ApiResponse.ok(result);
    }

    /**
     * 用表单里未保存的配置测试连接：新增账户前即可验证填写是否正确，
     * 密码留空时可通过 accountId 复用已存密码。
     */
    @PostMapping("/test-config")
    public ApiResponse<Map<String, Object>> testConfig(@Valid @RequestBody AccountTestRequest req) {
        return ApiResponse.ok(accountService.testConfig(req, req.getAccountId()));
    }
}