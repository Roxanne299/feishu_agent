package com.example.feishuagent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Feishu app credentials, bound from {@code feishu.*} in application.yml. */
@ConfigurationProperties(prefix = "feishu")
public record FeishuProperties(
        String appId, String appSecret, String encryptKey, String verificationToken) {}
