package com.mail.storage;

import com.mail.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 存储路由：按应用配置挑选默认存储实现，并可按 attachment 的 storage_type 切换。
 */
@Slf4j
@Service
public class StorageRouter {

    private final List<AttachmentStorage> storages;
    private final String defaultMode;

    public StorageRouter(List<AttachmentStorage> storages, AppProperties props) {
        this.storages = storages;
        this.defaultMode = props.getStorageMode();
        log.info("附件存储模式: {}", defaultMode);
    }

    public AttachmentStorage byType(String type) {
        return storages.stream()
                .filter(s -> s.type().equalsIgnoreCase(type))
                .findFirst().orElseGet(this::defaultStorage);
    }

    public AttachmentStorage defaultStorage() {
        return storages.stream()
                .filter(s -> s.type().equalsIgnoreCase(defaultMode))
                .findFirst()
                .orElseGet(() -> {
                    // 本地为兜底
                    AttachmentStorage lfs = storages.stream()
                            .filter(s -> "local".equalsIgnoreCase(s.type())).findFirst().orElse(null);
                    if (lfs == null) throw new IllegalStateException("没有可用存储实现");
                    return lfs;
                });
    }
}