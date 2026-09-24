package com.mail.account.dto;

import lombok.Data;

@Data
public class ProbeResult {
    private String protocol;
    private String host;
    private int port;
    private String encryption;
    private String smtpHost;
    private int smtpPort;
    private String smtpEncryption;
}