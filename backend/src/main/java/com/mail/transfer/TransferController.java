package com.mail.transfer;

import com.mail.auth.CurrentUser;
import com.mail.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 导入/导出（OST→PST 导出供 Outlook；导入 PST 预留）。
 */
@RestController
@RequestMapping("/api/transfer")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;

    @PostMapping("/export")
    public ApiResponse<Map<String, Object>> startExport(@RequestBody Map<String, Object> body) {
        Long userId = CurrentUser.requireId();
        Object accountId = body.get("accountId");
        Object folderIds = body.get("folderIds");
        Map<String, Object> task = transferService.startExport(
                accountId == null ? 0L : ((Number) accountId).longValue(),
                folderIds instanceof List<?> list ? list.stream().map(o -> ((Number) o).longValue()).toList()
                        : List.of());
        return ApiResponse.ok(task);
    }

    @GetMapping("/{taskId}")
    public ApiResponse<Map<String, Object>> status(@PathVariable String taskId) {
        Long userId = CurrentUser.requireId();
        return ApiResponse.ok(transferService.status(userId, taskId));
    }

    /** 导出完成后提供下载地址。 */
    @GetMapping("/{taskId}/download")
    public ResponseEntity<Resource> download(@PathVariable String taskId) {
        Long userId = CurrentUser.requireId();
        return transferService.download(userId, taskId);
    }
}