package com.mail.storage;

import java.io.InputStream;

/**
 * 附件存储抽象（本地 / MinIO 可切换）。返回存储引用字符串 storageRef。
 */
public interface AttachmentStorage {

    String type();

    /**
     * 保存流，返回 storageRef。
     */
    String save(String key, InputStream in, long size, String contentType);

    /**
     * 打开存储对象流。ref 由 save 返回。
     */
    InputStream open(String ref);

    /**
     * 删除存储对象。
     */
    void delete(String ref);
}