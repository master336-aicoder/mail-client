package com.mail.storage;

import com.mail.config.AppProperties;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * 本地文件系统存储实现。
 */
@Service
public class LocalFileStorage implements AttachmentStorage {

    private final Path baseDir;

    public LocalFileStorage(AppProperties props) {
        this.baseDir = Paths.get(props.getAttachDir());
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建附件目录", e);
        }
    }

    @Override
    public String type() {
        return "local";
    }

    @Override
    public String save(String key, InputStream in, long size, String contentType) {
        String ref = key == null ? UUID.randomUUID().toString() : key;
        Path target = baseDir.resolve(ref);
        try {
            Files.createDirectories(target.toAbsolutePath().getParent() == null ? baseDir : target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("本地附件保存失败", e);
        }
        return ref;
    }

    @Override
    public InputStream open(String ref) {
        Path p = baseDir.resolve(ref).normalize();
        try {
            return Files.newInputStream(p);
        } catch (IOException e) {
            throw new RuntimeException("本地附件打开失败", e);
        }
    }

    @Override
    public void delete(String ref) {
        try {
            Files.deleteIfExists(baseDir.resolve(ref));
        } catch (IOException ignored) {
        }
    }
}