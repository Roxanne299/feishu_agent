package com.example.feishuagent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/** Agent settings, bound from {@code agent.*} in application.yml. */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(String model, String sysPrompt) {

    private static final String DEFAULT_SYS_PROMPT = "你是一个飞书里的智能助手，用简洁的中文回答问题。";

    public AgentProperties {
        if (!StringUtils.hasText(sysPrompt)) {
            sysPrompt = DEFAULT_SYS_PROMPT;
        }
    }
}
