package com.example.feishuagent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/**
 * OpenAI-compatible endpoint, bound from {@code llm.*} in application.yml. When {@code baseUrl} is
 * set it takes precedence over {@link AgentProperties#model()}.
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(String baseUrl, String apiKey, String model) {

    boolean isConfigured() {
        return StringUtils.hasText(baseUrl);
    }

    boolean isComplete() {
        return isConfigured() && StringUtils.hasText(apiKey) && StringUtils.hasText(model);
    }
}
