-- =====================================================================
-- v10 证书例外（接受此证书 / 添加安全例外）
--
-- 内网或自建邮箱常见自签名、域名不匹配证书，JavaMail 默认拒掉握手。
-- 打开该开关后对相应协议设置 ssl.trust=* 与 checkserveridentity=false，
-- 属于用户显式授权的安全降级，默认关闭。
-- =====================================================================

ALTER TABLE mail_accounts
  ADD COLUMN ignore_cert_error TINYINT(1) NOT NULL DEFAULT 0 COMMENT '1=忽略 TLS 证书校验错误（自签名/域名不符/过期）';
