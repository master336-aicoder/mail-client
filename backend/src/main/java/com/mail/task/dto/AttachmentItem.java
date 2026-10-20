package com.mail.task.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 执行产物附件，供列表展示与下载。
 */
@Data
@AllArgsConstructor
public class AttachmentItem {

    private Long id;
    private String name;
    /** 文件当前大小（字节）；文件已被清理时为 null */
    private Long size;
    private String contentType;
    private LocalDateTime createdAt;
    private String downloadUrl;
    private boolean available;
}
