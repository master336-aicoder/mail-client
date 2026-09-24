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

    /** 归一化并校验 ref 落在 baseDir 之内，杜绝 ../ 路径穿越写到沙箱外。 */
    private Path resolveSafe(String ref) {
        Path root = baseDir.toAbsolutePath().normalize();
        Path target = root.resolve(ref).normalize();
        if (!target.startsWith(root)) {
            throw new SecurityException("非法的存储路径");
        }
        return target;
    }

    @Override
    public String save(String key, InputStream in, long size, String contentType) {
        String ref = key == null ? UUID.randomUUID().toString() : key;
        Path target = resolveSafe(ref);
        try {
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("本地附件保存失败", e);
        }
        return ref;
    }

    @Override
    public InputStream open(String ref) {
        Path p = resolveSafe(ref);
        try {
            return Files.newInputStream(p);
        } catch (IOException e) {
            throw new RuntimeException("本地附件打开失败", e);
        }
    }

    @Override
    public void delete(String ref) {
        try {
            Files.deleteIfExists(resolveSafe(ref));
        } catch (Exception ignored) {
        }
    }
}