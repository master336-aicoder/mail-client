package com.mail.mail;

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

    @Transactional
    public AttachmentMeta upload(MultipartFile file, Long accountId) {
        Long userId = CurrentUser.requireId();
        AttachmentStorage storage = storageRouter.defaultStorage();
        String ref;
        try (InputStream in = file.getInputStream()) {
            String key = userId + "/" + System.currentTimeMillis() + "-" + file.getOriginalFilename();
            ref = storage.save(key, in, file.getSize(), file.getContentType());
        } catch (IOException e) {
            throw new BizException("附件上传失败");
        }
        AttachmentMeta meta = new AttachmentMeta();
        meta.setUserId(userId);
        meta.setAccountId(accountId);
        meta.setName(file.getOriginalFilename());
        meta.setMimeType(file.getContentType());
        meta.setSize(file.getSize());
        meta.setStorageRef(ref);
        meta.setStorageType(storage.type());
        return metaRepository.save(meta);
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