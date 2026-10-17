package com.example.feishuagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.MsgContext;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.Peer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.ClientRequest;
import reactor.core.publisher.Mono;

class FeishuCardChannelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Records the context it is run with and answers with a canned reply. */
    private static final class FakeGateway implements Gateway {
        final List<MsgContext> contexts = new CopyOnWriteArrayList<>();
        private final String reply;

        FakeGateway(String reply) {
            this.reply = reply;
        }

        @Override
        public void bindMainAgent(HarnessAgent agent) {}

        @Override
        public Mono<Msg> run(MsgContext ctx, List<Msg> msgs) {
            contexts.add(ctx);
            return Mono.just(Msg.builder().role(MsgRole.ASSISTANT).textContent(reply).build());
        }
    }

    private static InboundMessage inbound(Peer peer, String senderId, String text) {
        return InboundMessage.builder(
                        AgentConfig.FEISHU_CHANNEL_ID,
                        peer,
                        List.of(Msg.builder().role(MsgRole.USER).textContent(text).build()))
                .senderId(senderId)
                .build();
    }

    private static FeishuCardChannel channel(FakeGateway gateway, RecordingExchange exchange) {
        FeishuCardChannel channel =
                new FeishuCardChannel(
                        AgentConfig.feishuChannelConfig(),
                        new FeishuCardSender(exchange.client(), () -> Mono.just("t-abc")));
        channel.init(gateway);
        return channel;
    }

    private static String cardMarkdown(ClientRequest request) throws Exception {
        JsonNode message = MAPPER.readTree(RecordingExchange.bodyOf(request));
        JsonNode card = MAPPER.readTree(message.get("content").asText());
        return card.get("body").get("elements").get(0).get("content").asText();
    }

    @Test
    void repliesToADirectChatWithACardWithoutTheReasoning() throws Exception {
        FakeGateway gateway = new FakeGateway("<think>reasoning</think>\n\n## 你好\n**粗体**");
        RecordingExchange exchange = new RecordingExchange(RecordingExchange.OK_RESPONSE);

        Msg reply =
                channel(gateway, exchange)
                        .dispatch(inbound(Peer.direct("oc_dm"), "ou_user", "你好"))
                        .block();

        assertThat(reply.getTextContent()).contains("<think>");
        ClientRequest request = exchange.onlyRequest();
        assertThat(request.url().getQuery()).isEqualTo("receive_id_type=chat_id");
        assertThat(MAPPER.readTree(RecordingExchange.bodyOf(request)).get("receive_id").asText())
                .isEqualTo("oc_dm");
        assertThat(cardMarkdown(request)).isEqualTo("## 你好\n**粗体**");
    }

    @Test
    void repliesInTheGroupTheMessageCameFrom() throws Exception {
        FakeGateway gateway = new FakeGateway("在群里回复");
        RecordingExchange exchange = new RecordingExchange(RecordingExchange.OK_RESPONSE);

        channel(gateway, exchange)
                .dispatch(inbound(Peer.group("oc_group"), "ou_user", "@bot 你好"))
                .block();

        ClientRequest request = exchange.onlyRequest();
        assertThat(request.url().getQuery()).isEqualTo("receive_id_type=chat_id");
        assertThat(MAPPER.readTree(RecordingExchange.bodyOf(request)).get("receive_id").asText())
                .isEqualTo("oc_group");
    }

    @Test
    void keepsOneSessionPerChat() {
        FakeGateway gateway = new FakeGateway("回复");
        RecordingExchange exchange = new RecordingExchange(RecordingExchange.OK_RESPONSE);
        FeishuCardChannel channel = channel(gateway, exchange);

        channel.dispatch(inbound(Peer.direct("oc_a"), "ou_a", "你好")).block();
        channel.dispatch(inbound(Peer.direct("oc_a"), "ou_a", "还记得吗")).block();
        channel.dispatch(inbound(Peer.direct("oc_b"), "ou_b", "你好")).block();

        assertThat(gateway.contexts).hasSize(3);
        // The session the agent runs in is keyed by MsgContext.canonicalKey().
        String first = gateway.contexts.get(0).canonicalKey();
        assertThat(gateway.contexts.get(1).canonicalKey()).isEqualTo(first);
        assertThat(gateway.contexts.get(2).canonicalKey()).isNotEqualTo(first);
    }

    @Test
    void sendsANoticeRatherThanAnEmptyCard() throws Exception {
        FakeGateway gateway = new FakeGateway("<think>只有推理</think>");
        RecordingExchange exchange = new RecordingExchange(RecordingExchange.OK_RESPONSE);

        channel(gateway, exchange).dispatch(inbound(Peer.direct("oc_dm"), "ou_user", "你好")).block();

        assertThat(cardMarkdown(exchange.onlyRequest())).isEqualTo(ReplySanitizer.EMPTY_REPLY);
    }
}
