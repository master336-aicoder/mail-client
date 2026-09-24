package com.mail.storage;

import com.mail.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.UUID;

/**
 * MinIO 对象存储实现。仅在 {@code mail.app.storage-mode=minio} 时激活。
 */
@Service
@ConditionalOnProperty(name = "mail.app.storage-mode", havingValue = "minio")
public class MinioStorage implements AttachmentStorage {

    private final MinioClient client;
    private final String bucket;

    public MinioStorage(MinioProperties props) {
        this.client = MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .build();
        this.bucket = props.getBucket();
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 初始化失败: " + e.getMessage(), e);
        }
    }

    @Override
    public String type() {
        return "minio";
    }

    @Override
    public String save(String key, InputStream in, long size, String contentType) {
        String object = key == null ? UUID.randomUUID().toString() : key;
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket).object(object)
                    .stream(in, size, -1)
                    .contentType(contentType)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("MinIO 保存失败", e);
        }
        return object;
    }

    @Override
    public InputStream open(String ref) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(ref).build());
        } catch (Exception e) {
            throw new RuntimeException("MinIO 打开失败", e);
        }
    }

    @Override
    public void delete(String ref) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(ref).build());
        } catch (Exception ignored) {
        }
    }
}