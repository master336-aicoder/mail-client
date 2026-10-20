package com.mail.transfer;

import com.mail.auth.CurrentUser;
import com.mail.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 导入/导出：PST/OST→邮件（导入到账户文件夹，异步任务）；邮件→EML zip 导出供 Outlook。
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
        Object format = body.get("format");
        Map<String, Object> task = transferService.startExport(
                accountId == null ? 0L : ((Number) accountId).longValue(),
                folderIds instanceof List<?> list ? list.stream().map(o -> ((Number) o).longValue()).toList()
                        : List.of(),
                format == null ? null : String.valueOf(format));
        return ApiResponse.ok(task);
    }

    /** 第一步：上传 PST 文件落盘，返回 uploadId。 */
    @PostMapping("/import/upload")
    public ApiResponse<Map<String, Object>> uploadImport(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(transferService.uploadImport(file));
    }

    /** 第二步：按 uploadId 提交异步导入任务。 */
    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> startImport(@RequestBody Map<String, Object> body) {
        Object accountId = body.get("accountId");
        Object uploadId = body.get("uploadId");
        Object folderName = body.get("folderName");
        Map<String, Object> task = transferService.startImport(
                uploadId == null ? null : String.valueOf(uploadId),
                accountId == null ? null : ((Number) accountId).longValue(),
                folderName == null ? null : String.valueOf(folderName));
        return ApiResponse.ok(task);
    }

    @GetMapping("/{taskId}")
    public ApiResponse<Map<String, Object>> status(@PathVariable Long taskId) {
        Long userId = CurrentUser.requireId();
        return ApiResponse.ok(transferService.status(userId, taskId));
    }
}