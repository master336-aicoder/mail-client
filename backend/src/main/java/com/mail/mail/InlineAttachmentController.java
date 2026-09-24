package com.mail.mail;

import com.mail.auth.JwtService;
import com.mail.common.BizException;
import com.mail.mail.domain.AttachmentMeta;
import com.mail.mail.domain.AttachmentMetaRepository;
import com.mail.storage.AttachmentStorage;
import com.mail.storage.StorageRouter;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 内嵌资源读取端点（白名单 permitAll）。
 *
 * <p>{@code <img>} 标签无法携带 Authorization 头，因此这里用 URL 中的
 * access_token 校验用户身份（复用登录 JWT），并仅对内嵌图片（disposition=inline
 * 或带 content_id 的附件）提供 inline 输出，避免泄露出站附件内容。
 */
@RestController
@RequestMapping("/api/inline")
@RequiredArgsConstructor
public class InlineAttachmentController {

    private final AttachmentMetaRepository metaRepository;
    private final StorageRouter storageRouter;
    private final JwtService jwtService;

    @GetMapping("/{id}")
    public ResponseEntity<Resource> inline(
            @PathVariable Long id,
            @RequestParam(name = "access_token", required = false) String token) {
        Long userId = resolveUid(token);
        AttachmentMeta meta = metaRepository.findById(id).orElseThrow(() -> new BizException(40400, "资源不存在"));
        if (!meta.getUserId().equals(userId)) throw new BizException(40300, "无权访问");
        // 仅允许内嵌资源（带 content_id）通过此端点输出
        if (meta.getContentId() == null || meta.getContentId().isBlank()) {
            throw new BizException(40300, "该资源不可内嵌展示");
        }
        AttachmentStorage storage = storageRouter.byType(meta.getStorageType());
        InputStream in = storage.open(meta.getStorageRef());
        InputStreamResource res = new InputStreamResource(in);
        String mime = meta.getMimeType() == null
                ? "application/octet-stream" : meta.getMimeType();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mime))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePrivate())
                .body(res);
    }

    private Long resolveUid(String token) {
        if (token == null || token.isBlank()) throw new BizException(40100, "未登录");
        try {
            Claims claims = jwtService.parse(token);
            Long uid = claims.get("uid", Long.class);
            if (uid == null) throw new BizException(40100, "未登录");
            return uid;
        } catch (Exception e) {
            throw new BizException(40100, "登录已失效");
        }
    }
}
