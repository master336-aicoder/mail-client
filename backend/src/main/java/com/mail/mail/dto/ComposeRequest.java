package com.mail.mail.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 发送/草稿请求。
 */
@Data
public class ComposeRequest {

    @NotNull(message = "请选择账户")
    private Long accountId;

    private List<String> to = List.of();
    private List<String> cc = List.of();
    private List<String> bcc = List.of();

    private String subject;

    private String bodyHtml;
    private String bodyText;

    private List<Long> attachments = List.of();
}