package com.mail.task;

import com.mail.auth.CurrentUser;
import com.mail.task.domain.TaskAttachment;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务产物附件下载：列表里的 downloadUrl 指向这里，按附件 id 取文件并校验归属。
 */
@RestController
@RequestMapping("/api/task-attachments")
@RequiredArgsConstructor
public class TaskAttachmentController {

    private final TaskService taskService;
    private final AttachmentFiles attachmentFiles;

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        TaskAttachment a = taskService.attachmentForDownload(CurrentUser.requireId(), id);
        return attachmentFiles.serve(a);
    }
}
