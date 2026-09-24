package com.mail.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MinIO 对象存储配置 {@code mail.storage.minio.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mail.storage.minio")
public class MinioProperties {

    private String endpoint = "http://localhost:9000";
    private String accessKey = "admin";
    private String secretKey = "admin123456";
    private String bucket = "mail-attachments";
}