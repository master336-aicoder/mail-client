package com.mail.common;

import com.mail.config.AppProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 出网主机校验：拦截指向回环 / 私有 / 链路本地 / 组播 / 云元数据地址的邮件服务器主机，
 * 防止绑定账户时把服务端变成 SSRF 探针（同步/发信会主动外连该 host:port）。
 *
 * <p>本地若确需绑定内网邮件服务器，可经 {@code mail.app.allow-private-host=true}（dev profile）放行。
 */
@Component
public class HostGuard {

    private final boolean allowPrivate;

    public HostGuard(AppProperties props) {
        this.allowPrivate = props.isAllowPrivateHost();
    }

    /** 校验单个主机；blank 直接返回。非法/被禁地址抛 BizException(40030)。 */
    public void check(String host, String label) {
        if (host == null || host.isBlank()) return;
        if (allowPrivate) return;
        String h = host.trim();
        // 去掉可能的协议/端口片段（防御式解析）
        int scheme = h.indexOf("://");
        if (scheme >= 0) h = h.substring(scheme + 3);
        int colon = h.lastIndexOf(':');
        int slash = h.indexOf('/');
        if (colon > 0 && (slash < 0 || colon < slash)) h = h.substring(0, colon);
        try {
            for (InetAddress addr : InetAddress.getAllByName(h)) {
                if (isBlocked(addr)) {
                    throw new BizException(40030, label + "地址不合法：不允许指向内网/回环/元数据地址");
                }
            }
        } catch (UnknownHostException e) {
            throw new BizException(40031, label + "域名无法解析");
        }
    }

    private static boolean isBlocked(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int o0 = b[0] & 0xFF, o1 = b[1] & 0xFF;
            // 100.64.0.0/10 CGNAT, 169.254.0.0/16 (含云元数据), 192.0.0.0/24, 0.0.0.0/8
            if (o0 == 100 && o1 >= 64 && o1 <= 127) return true;
            if (o0 == 169 && o1 == 254) return true;
            if (o0 == 192 && o1 == 0) return true;
            if (o0 == 0) return true;
        }
        if (b.length == 16) {
            // fc00::/7 ULA
            if ((b[0] & 0xFE) == 0xFC) return true;
        }
        return false;
    }
}
