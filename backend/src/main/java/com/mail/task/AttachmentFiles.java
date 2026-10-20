package com.mail.task;

import com.mail.common.BizException;
import com.mail.task.domain.TaskAttachment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 任务产物文件的落点与下载。所有路径都以产物根目录为界，防止记录里的相对路径穿越到别处。
 */
@Component
public class AttachmentFiles {

    @Value("${mail.app.pst-export-dir:./data/exports}")
    private String root;

    private Path rootPath() {
        return Paths.get(root).toAbsolutePath().normalize();
    }

    /** 解析相对路径，越出产物根目录直接报错。 */
    public Path resolve(String relative) {
        if (relative == null || relative.isBlank()) throw new BizException("任务没有产物文件");
        Path base = rootPath();
        Path file = base.resolve(relative).normalize();
        if (!file.startsWith(base)) throw new BizException("非法的产物路径");
        return file;
    }

    /** 文件字节数；不存在或路径非法时返回 -1。 */
    public long sizeOf(String relative) {
        try {
            Path p = resolve(relative);
            return Files.isRegularFile(p) ? Files.size(p) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 产物所在目录，导出时先建好。 */
    public Path ensureDir(String relativeDir) {
        try {
            Path dir = resolve(relativeDir);
            Files.createDirectories(dir);
            return dir;
        } catch (Exception e) {
            throw new BizException("无法创建产物目录：" + e.getMessage());
        }
    }

    public ResponseEntity<Resource> serve(TaskAttachment a) {
        Path file = resolve(a.getPath());
        if (!Files.isRegularFile(file)) throw new BizException("产物文件不存在或已清理");
        String name = a.getName() == null || a.getName().isBlank() ? file.getFileName().toString() : a.getName();
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        MediaType type = a.getContentType() == null || a.getContentType().isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(a.getContentType());
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .body(new FileSystemResource(file.toFile()));
    }
}
