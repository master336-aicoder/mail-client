package com.mail.mail.dto;

import com.mail.mail.domain.AttachmentMeta;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 邮件详情。
 */
@Data
public class MessageDetail {
    private Long id;
    private String subject;
    private String sender;
    private String senderEmail;
    private String recipients;
    private String cc;
    private LocalDateTime date;
    private Integer flags;
    private Boolean read;
    private Boolean starred;
    private Boolean important;
    private String bodyHtml;
    private String bodyText;
    private List<AttachmentMeta> attachments;
    private String ostRef;

    /** 是否草稿（草稿箱中的邮件）。 */
    private Boolean isDraft;

    /** 草稿所属账户 id，用于“编辑/发送草稿”时定位发件账户。 */
    private Long accountId;

    /** 草稿的收件人/抄送/密送（仅草稿需要，供编辑与发送回填）。 */
    private String bcc;
}