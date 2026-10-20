package com.mail.protocol;

import java.util.Properties;

/**
 * 加密方式 → JavaMail 属性的统一映射，收信（imap/pop3）与发信（smtp）各自独立调用。
 * SSL=隐式 TLS（连接即握手），STARTTLS=明文连接后升级（要求升级成功，不允许静默降级），NONE=不加密。
 * 同时统一设置连接/读写超时，避免某个服务器不回包时任务线程被永久占住。
 */
public final class TlsProps {

    private static final String CONNECT_TIMEOUT_MS = "15000";
    private static final String READ_TIMEOUT_MS = "60000";
    private static final String WRITE_TIMEOUT_MS = "60000";

    private TlsProps() {
    }

    public static void apply(Properties props, String prefix, String encryption) {
        apply(props, prefix, encryption, false);
    }

    /**
     * @param ignoreCertError 用户显式勾选「接受此证书 / 添加安全例外」：
     *                        对 SSL 隐式 TLS 与 STARTTLS 升级后的连接都放行无效证书。
     */
    public static void apply(Properties props, String prefix, String encryption, boolean ignoreCertError) {
        // 无超时的话，服务器不回包会把收信任务线程永久挂住（实测本地假 POP3 不回包即复现）。
        props.put(prefix + ".connectiontimeout", CONNECT_TIMEOUT_MS);
        props.put(prefix + ".timeout", READ_TIMEOUT_MS);
        if ("mail.smtp".equals(prefix)) props.put(prefix + ".writetimeout", WRITE_TIMEOUT_MS);
        if ("SSL".equalsIgnoreCase(encryption)) {
            props.put(prefix + ".ssl.enable", "true");
        } else if ("STARTTLS".equalsIgnoreCase(encryption)) {
            props.put(prefix + ".starttls.enable", "true");
            props.put(prefix + ".starttls.required", "true");
        }
        if (!ignoreCertError) return;
        // ssl.trust=* 放行证书链与主机名校验；STARTTLS 升级复用同一协议的 ssl.* 配置，无需另设前缀。
        props.put(prefix + ".ssl.trust", "*");
        props.put(prefix + ".ssl.checkserveridentity", "false");
    }
}
