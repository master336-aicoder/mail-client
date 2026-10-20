package com.mail.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 站内信式消息通知：新邮件到达、收发异常、文件夹清空等事件的持久化提醒。
 */
@Getter
@Setter
@Entity
@Table(name = "notifications")
@EntityListeners(AuditingEntityListener.class)
public class Notification {

    /** 新邮件到达（一轮收信汇总一条） */
    public static final String MAIL_NEW = "MAIL_NEW";
    /** 收信失败 */
    public static final String RECEIVE_FAILED = "RECEIVE_FAILED";
    /** 发信失败 */
    public static final String SEND_FAILED = "SEND_FAILED";
    /** 文件夹被清空 */
    public static final String FOLDER_CLEARED = "FOLDER_CLEARED";
    /** 邮件账户被移除 */
    public static final String ACCOUNT_DELETED = "ACCOUNT_DELETED";
    /** 邮件账户本地数据重置（清空邮件+附件后重新拉取），服务器邮件不受影响 */
    public static final String ACCOUNT_RESET = "ACCOUNT_RESET";
    /** 管理员发布的站内公告（站内信），一次发布按批次号扇出给每个收件人 */
    public static final String ANNOUNCEMENT = "ANNOUNCEMENT";
    /** 系统资源预警（CPU/内存/磁盘超过阈值） */
    public static final String SYSTEM_ALERT = "SYSTEM_ALERT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(length = 1000)
    private String content;

    /** 点击通知跳转的前端路由，如 /mail/folder/12 */
    @Column(length = 255)
    private String link;

    /** 公告的发布人；系统事件通知为 null。 */
    @Column(name = "sender_id")
    private Long senderId;

    /** 公告批次号：同一次发布共用，用于发布历史与阅读进度统计。 */
    @Column(name = "batch_no", length = 64)
    private String batchNo;

    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
