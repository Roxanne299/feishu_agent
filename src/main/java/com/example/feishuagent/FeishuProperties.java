package com.example.feishuagent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/** Feishu app credentials, bound from {@code feishu.*} in application.yml. */
@ConfigurationProperties(prefix = "feishu")
public record FeishuProperties(
        String appId, String appSecret, String encryptKey, String verificationToken, String apiBase) {

    private static final String DEFAULT_API_BASE = "https://open.feishu.cn";

    public FeishuProperties {
        if (!StringUtils.hasText(apiBase)) {
            apiBase = DEFAULT_API_BASE;
        }
    }
}
