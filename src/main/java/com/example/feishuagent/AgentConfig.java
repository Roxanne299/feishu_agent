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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** Wires one HarnessAgent and the Feishu channel into an AgentScope gateway. */
@Configuration
public class AgentConfig {

    public static final String AGENT_ID = "main";
    public static final String FEISHU_CHANNEL_ID = "feishu";

    @Bean
    public HarnessAgent mainAgent(AgentProperties agent, LlmProperties llm) throws IOException {
        Path workspace = Files.createDirectories(Paths.get(".agentscope/workspace"));
        HarnessAgent.Builder builder =
                HarnessAgent.builder()
                        .name("feishu-assistant")
                        .agentId(AGENT_ID)
                        .sysPrompt(agent.sysPrompt())
                        .workspace(workspace);
        if (llm.isConfigured()) {
            // OpenAI-compatible endpoint, e.g. MiniMax
            builder.model(openAiCompatibleModel(llm));
        } else if (StringUtils.hasText(agent.model())) {
            // e.g. "dashscope:qwen-plus", API key read from the provider's env var
            builder.model(agent.model());
        } else {
            throw new IllegalStateException(
                    "No model configured: set llm.base-url + llm.api-key + llm.model,"
                            + " or agent.model");
        }
        return builder.build();
    }

    private static Model openAiCompatibleModel(LlmProperties llm) {
        if (!llm.isComplete()) {
            throw new IllegalStateException(
                    "llm.base-url is set, so llm.api-key and llm.model are required");
        }
        return OpenAIChatModel.builder()
                .baseUrl(llm.baseUrl())
                .apiKey(llm.apiKey())
                .modelName(llm.model())
                .build();
    }

    @Bean
    public FeishuChannel feishuChannel(FeishuProperties feishu) {
        Map<String, Object> props = new HashMap<>();
        props.put("appId", feishu.appId());
        props.put("appSecret", feishu.appSecret());
        if (StringUtils.hasText(feishu.encryptKey())) {
            props.put("encryptKey", feishu.encryptKey());
        }
        if (StringUtils.hasText(feishu.verificationToken())) {
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
}
