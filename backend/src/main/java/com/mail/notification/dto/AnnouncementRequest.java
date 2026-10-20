package com.mail.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 管理员发布站内公告：targetAll 为真时发给全体用户，否则只发给 userIds。
 */
@Data
public class AnnouncementRequest {

    @NotBlank(message = "请填写公告标题")
    @Size(max = 128, message = "标题最长 128 字")
    private String title;

    @Size(max = 1000, message = "正文最长 1000 字")
    private String content;

    /** 可选：点击公告跳转的前端路由，如 /settings/accounts。 */
    @Size(max = 255, message = "跳转链接最长 255 字")
    private String link;

    private Boolean targetAll;

    private List<Long> userIds;
}
