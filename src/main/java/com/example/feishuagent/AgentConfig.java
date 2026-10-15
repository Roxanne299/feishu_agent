package com.example.feishuagent;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.channel.feishu.FeishuChannel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.GatewayBootstrap;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires one HarnessAgent and the Feishu channel into an AgentScope gateway. */
@Configuration
public class AgentConfig {

    public static final String AGENT_ID = "main";
    public static final String FEISHU_CHANNEL_ID = "feishu";

    @Bean
    public HarnessAgent mainAgent(
            @Value("${agent.model}") String modelId,
            @Value("${agent.sys-prompt}") String sysPrompt,
            @Value("${llm.base-url}") String baseUrl,
            @Value("${llm.api-key}") String apiKey,
            @Value("${llm.model}") String modelName)
            throws IOException {
        Path workspace = Files.createDirectories(Paths.get(".agentscope/workspace"));
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name("feishu-assistant")
                        .agentId(AGENT_ID)
                        .sysPrompt(sysPrompt)
                        .workspace(workspace);
        if (hasText(baseUrl)) {
            // OpenAI-compatible endpoint, e.g. MiniMax
            if (!hasText(apiKey) || !hasText(modelName)) {
                throw new IllegalStateException(
                        "LLM_BASE_URL is set, so LLM_API_KEY and LLM_MODEL are required");
            }
            Model model =
                    OpenAIChatModel.builder()
                            .baseUrl(baseUrl)
                            .apiKey(apiKey)
                            .modelName(modelName)
                            .build();
            builder.model(model);
        } else {
            // e.g. "dashscope:qwen-plus", API key read from the provider's env var
            builder.model(modelId);
        }
        return builder.build();
    }

    @Bean
    public FeishuChannel feishuChannel(FeishuProperties feishu) {
        Map<String, Object> props = new HashMap<>();
        props.put("appId", feishu.appId());
        props.put("appSecret", feishu.appSecret());
        if (hasText(feishu.encryptKey())) {
            props.put("encryptKey", feishu.encryptKey());
        }
        if (hasText(feishu.verificationToken())) {
            props.put("verificationToken", feishu.verificationToken());
        }
        return FeishuChannel.fromProperties(
                FEISHU_CHANNEL_ID, ChannelConfig.of(FEISHU_CHANNEL_ID, AGENT_ID), props);
    }

    @Bean(destroyMethod = "stop")
    public GatewayBootstrap gateway(HarnessAgent mainAgent, FeishuChannel feishuChannel) {
        return GatewayBootstrap.builder()
                .agent(AGENT_ID, mainAgent)
                .mainAgent(AGENT_ID)
                .channel(feishuChannel)
                .build()
                .start();
    }

    static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
