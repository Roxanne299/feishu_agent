package com.example.feishuagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.ClientRequest;
import reactor.core.publisher.Mono;

class FeishuCardSenderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void wrapsMarkdownInACardTwoPointZero() throws Exception {
        JsonNode message = MAPPER.readTree(FeishuCardSender.requestBody("oc_123", "## 标题"));

        assertThat(message.get("receive_id").asText()).isEqualTo("oc_123");
        assertThat(message.get("msg_type").asText()).isEqualTo("interactive");
        // Feishu wants the card as a string, not as a nested object.
        assertThat(message.get("content").isTextual()).isTrue();

        JsonNode card = MAPPER.readTree(message.get("content").asText());
        assertThat(card.get("schema").asText()).isEqualTo("2.0");
        JsonNode element = card.get("body").get("elements").get(0);
        assertThat(element.get("tag").asText()).isEqualTo("markdown");
        assertThat(element.get("content").asText()).isEqualTo("## 标题");
    }

    @Test
    void postsTheCardWithTheTenantToken() {
        RecordingExchange exchange = new RecordingExchange(RecordingExchange.OK_RESPONSE);
        FeishuCardSender sender =
                new FeishuCardSender(exchange.client(), () -> Mono.just("t-abc"));

        String messageId = sender.sendMarkdown("chat_id", "oc_123", "**粗体**").block();

        assertThat(messageId).isEqualTo("om_1");
        ClientRequest request = exchange.onlyRequest();
        assertThat(request.url().getPath()).isEqualTo("/open-apis/im/v1/messages");
        assertThat(request.url().getQuery()).isEqualTo("receive_id_type=chat_id");
        assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer t-abc");
        assertThat(RecordingExchange.bodyOf(request)).contains("\\\"tag\\\":\\\"markdown\\\"");
    }

    @Test
    void asksForTheTokenOncePerSend() {
        RecordingExchange exchange = new RecordingExchange(RecordingExchange.OK_RESPONSE);
        AtomicInteger tokenCalls = new AtomicInteger();
        FeishuCardSender sender =
                new FeishuCardSender(
                        exchange.client(),
                        () -> Mono.fromSupplier(() -> "t-" + tokenCalls.incrementAndGet()));

        sender.sendMarkdown("chat_id", "oc_123", "一").block();
        sender.sendMarkdown("chat_id", "oc_123", "二").block();

        // Caching lives in FeishuAccessTokenProvider, so the sender must not cache on its own.
        assertThat(tokenCalls).hasValue(2);
        assertThat(exchange.requests).hasSize(2);
    }

    @Test
    void failsWhenFeishuRejectsTheCard() {
        RecordingExchange exchange =
                new RecordingExchange("{\"code\":230001,\"msg\":\"invalid receive_id\"}");
        FeishuCardSender sender =
                new FeishuCardSender(exchange.client(), () -> Mono.just("t-abc"));

        assertThatThrownBy(() -> sender.sendMarkdown("chat_id", "nope", "正文").block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("230001")
                .hasMessageContaining("invalid receive_id");
    }
}
