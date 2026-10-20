package com.mail.mail;

import com.mail.auth.CurrentUser;
import com.mail.common.ApiResponse;
import com.mail.mail.dto.BatchFlagsRequest;
import com.mail.mail.dto.BatchIdsRequest;
import com.mail.mail.dto.BatchMoveRequest;
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

    @PutMapping("/messages/draft/{id}")
    public ApiResponse<Map<String, Object>> updateDraft(@PathVariable Long id, @RequestBody ComposeRequest req) {
        mailService.updateDraft(id, req);
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

    /** 批量标记：已读 / 星标 / 重要，未传的项保持不变。 */
    @PostMapping("/messages/batch-flags")
    public ApiResponse<Map<String, Object>> batchFlags(@Valid @RequestBody BatchFlagsRequest req) {
        int updated = mailService.batchUpdateFlags(CurrentUser.requireId(), req.getIds(), req);
        return ApiResponse.ok(Map.of("updated", updated));
    }

    @PostMapping("/messages/batch-delete")
    public ApiResponse<Map<String, Object>> batchDelete(@Valid @RequestBody BatchIdsRequest req) {
        int deleted = mailService.batchDelete(CurrentUser.requireId(), req.getIds());
        return ApiResponse.ok(Map.of("deleted", deleted));
    }

    /** 批量移动到指定文件夹（目标须为账户级文件夹）。 */
    @PostMapping("/messages/batch-move")
    public ApiResponse<Map<String, Object>> batchMove(@Valid @RequestBody BatchMoveRequest req) {
        return ApiResponse.ok(Map.of("moved", mailService.batchMove(CurrentUser.requireId(), req)));
    }

    @PostMapping("/folders/{folderId}/mark-read")
    public ApiResponse<Map<String, Object>> markFolderRead(@PathVariable Long folderId) {
        return ApiResponse.ok(Map.of("updated", mailService.markFolderRead(folderId)));
    }

    /** 清空文件夹：合并视图会连带各账户同名文件夹，属高影响操作，前端需二次确认。 */
    @PostMapping("/folders/{folderId}/clear")
    public ApiResponse<Map<String, Object>> clearFolder(@PathVariable Long folderId) {
        return ApiResponse.ok(Map.of("deleted", mailService.clearFolder(folderId)));
    }
}