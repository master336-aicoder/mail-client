package com.mail.mail.domain;

/**
 * 同步阶段临时持有的内嵌资源（正文图片等）。仅内存存活，不落库；
 * 当所属邮件保存到数据库后，AccountSync 会据此写出附件元数据并重写 cid 引用。
 */
public class PendingInlinePart {
    /** Content-ID，不含尖括号，映射到 attachment_meta.content_id */
    public String contentId;
    public String fileName;
    public String mimeType;
    public byte[] data;

    public PendingInlinePart(String contentId, String fileName, String mimeType, byte[] data) {
        this.contentId = contentId;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.data = data;
    }
}