package com.example.feishuagent;

import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.gateway.GatewayBootstrap;
import io.agentscope.harness.agent.gateway.channel.chatui.SendOptions;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** {@code GET /chat?q=...} — talk to the agent without Feishu, to check the model works. */
@RestController
public class LocalChatController {

    private final GatewayBootstrap gateway;

    public LocalChatController(GatewayBootstrap gateway) {
        this.gateway = gateway;
    }

    @GetMapping("/chat")
    public Mono<Map<String, String>> chat(
            @RequestParam String q, @RequestParam(defaultValue = "local-user") String user) {
        return gateway.chatUiChannel()
                .send(SendOptions.userId(user), q)
                .map(Msg::getTextContent)
                .defaultIfEmpty("")
                .map(text -> Map.of("reply", text));
    }
}
