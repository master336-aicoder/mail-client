package com.mail.mail;

import com.mail.common.ApiResponse;
import com.mail.mail.dto.ComposeRequest;
import com.mail.mail.dto.MessageDetail;
import com.mail.mail.dto.MessageListItem;
import com.mail.mail.dto.UpdateFlagsRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MailController {

    private final MailService mailService;

    @GetMapping("/folders/{folderId}/messages")
    public ApiResponse<MessageListItem.Page> list(@PathVariable Long folderId,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "50") int size,
                                                   @RequestParam(required = false) String flag,
                                                   @RequestParam(required = false) String q,
                                                   @RequestParam(required = false) Long accountId) {
        return ApiResponse.ok(mailService.list(folderId, page, size, flag, q, accountId));
    }

    @GetMapping("/messages/{id}")
    public ApiResponse<MessageDetail> detail(@PathVariable Long id) {
        return ApiResponse.ok(mailService.detail(id));
    }

    @PostMapping("/messages")
    public ApiResponse<Map<String, Object>> send(@Valid @RequestBody ComposeRequest req) {
        Long id = mailService.send(req);
        return ApiResponse.ok(Map.of("messageId", id));
    }

    @PostMapping("/messages/draft")
    public ApiResponse<Map<String, Object>> saveDraft(@RequestBody ComposeRequest req) {
        Long id = mailService.saveDraft(req);
        return ApiResponse.ok(Map.of("messageId", id));
    }

    @PutMapping("/messages/{id}/flags")
    public ApiResponse<Void> updateFlags(@PathVariable Long id, @RequestBody UpdateFlagsRequest req) {
        mailService.updateFlags(id, req);
        return ApiResponse.ok();
    }

    @DeleteMapping("/messages/{id}")
    public ApiResponse<Void> markDeleted(@PathVariable Long id) {
        mailService.markDeleted(id);
        return ApiResponse.ok();
    }
}