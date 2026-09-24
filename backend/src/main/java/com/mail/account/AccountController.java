package com.mail.account;

import com.mail.account.domain.MailAccount;
import com.mail.account.dto.AccountRequest;
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
                                           @RequestBody com.mail.account.dto.AccountUpdateRequest req) {
        return ApiResponse.ok(accountService.updateSync(id, req.getSyncIntervalSec(), req.getActive()));
    }
}