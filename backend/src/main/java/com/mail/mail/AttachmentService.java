package com.mail.mail;

import com.mail.account.domain.MailAccountRepository;
import com.mail.auth.CurrentUser;
import com.mail.common.BizException;
import com.mail.mail.domain.AttachmentMeta;
import com.mail.mail.domain.AttachmentMetaRepository;
import com.mail.storage.AttachmentStorage;
import com.mail.storage.StorageRouter;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 附件上传/下载。
 */
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private final AttachmentMetaRepository metaRepository;
    private final StorageRouter storageRouter;
    private final MailAccountRepository accountRepository;

    @Transactional
    public AttachmentMeta upload(MultipartFile file, Long accountId) {
        Long userId = CurrentUser.requireId();
        if (accountId != null) {
            var a = accountRepository.findById(accountId).orElse(null);
            if (a == null || !userId.equals(a.getUserId())) {
                throw new BizException(40300, "无权操作该账户");
            }
        }
        AttachmentStorage storage = storageRouter.defaultStorage();
        String safeName = sanitizeFileName(file.getOriginalFilename());
        String ref;
        try (InputStream in = file.getInputStream()) {
            String key = userId + "/" + System.currentTimeMillis() + "-" + safeName;
            ref = storage.save(key, in, file.getSize(), file.getContentType());
        } catch (IOException e) {
            throw new BizException("附件上传失败");
        }
        AttachmentMeta meta = new AttachmentMeta();
        meta.setUserId(userId);
        meta.setAccountId(accountId);
        meta.setName(safeName);
        meta.setMimeType(file.getContentType());
        meta.setSize(file.getSize());
        meta.setStorageRef(ref);
        meta.setStorageType(storage.type());
        return metaRepository.save(meta);
    }

    /** 只取文件名末段并剔除路径分隔符与上级目录片段，防止存储 key 被注入 ../ */
    private static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "file";
        String n = name.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        n = n.replace("..", "").trim();
        return n.isBlank() ? "file" : n;
    }

    /**
     * 转发用：给同一份内容新建一条附件记录（文件本体不复制，存储里从不物理删除）。
     * 不能直接把原 id 交给发送请求——发送会把引用的附件改挂到新邮件下，原邮件的附件列表就空了。
     */
    @Transactional
    public AttachmentMeta copy(Long id) {
        Long userId = CurrentUser.requireId();
        AttachmentMeta src = metaRepository.findById(id).orElseThrow(() -> new BizException("附件不存在"));
        if (!src.getUserId().equals(userId)) throw new BizException(40300, "无权访问");
        AttachmentMeta copy = new AttachmentMeta();
        copy.setUserId(userId);
        copy.setAccountId(src.getAccountId());
        copy.setName(src.getName());
        copy.setMimeType(src.getMimeType());
        copy.setSize(src.getSize());
        copy.setStorageRef(src.getStorageRef());
        copy.setStorageType(src.getStorageType());
        return metaRepository.save(copy);
    }

    @Transactional(readOnly = true)
    public ResponseEntity<Resource> download(Long id) {
        Long userId = CurrentUser.requireId();
        AttachmentMeta meta = metaRepository.findById(id).orElseThrow(() -> new BizException("附件不存在"));
        if (!meta.getUserId().equals(userId)) throw new BizException(40300, "无权访问");
        AttachmentStorage storage = storageRouter.byType(meta.getStorageType());
        InputStream in = storage.open(meta.getStorageRef());
        InputStreamResource res = new InputStreamResource(in);
        String encoded = URLEncoder.encode(meta.getName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(meta.getMimeType() == null ? "application/octet-stream" : meta.getMimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .body(res);
    }
}