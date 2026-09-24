package com.mail.mail;

import com.mail.common.ApiResponse;
import com.mail.mail.domain.AttachmentMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    @PostMapping
    public ApiResponse<AttachmentMeta> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(required = false) Long accountId) {
        return ApiResponse.ok(attachmentService.upload(file, accountId));
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        return attachmentService.download(id);
    }
}