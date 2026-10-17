package com.example.feishuagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.extensions.channel.feishu.FeishuAccessTokenProvider;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Posts a reply to Feishu as an interactive card, so the markdown in it renders as rich text.
 *
 * <p>agentscope's own {@code FeishuOutboundClient} hardcodes {@code msg_type=text}, and a Feishu
 * text message is shown verbatim — that is why {@code **bold**} and {@code ##} reached users as
 * literal characters. The card body uses card JSON 2.0's {@code markdown} component, which renders
 * headings, lists, links, quotes, tables, code blocks and dividers (lists and code blocks need a
 * Feishu client of 7.6 or newer).
 */
class FeishuCardSender {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SEND_PATH = "/open-apis/im/v1/messages";

    private final WebClient client;
    private final Supplier<Mono<String>> tenantToken;

    FeishuCardSender(WebClient client, Supplier<Mono<String>> tenantToken) {
        this.client = client;
        this.tenantToken = tenantToken;
    }

    /** Uses agentscope's token provider, which caches the tenant_access_token until it expires. */
    static FeishuCardSender create(FeishuProperties feishu) {
        FeishuAccessTokenProvider tokens =
                new FeishuAccessTokenProvider(feishu.apiBase(), feishu.appId(), feishu.appSecret());
        return new FeishuCardSender(
                WebClient.builder().baseUrl(feishu.apiBase()).build(), tokens::token);
    }

    /**
     * @param receiveIdType {@code chat_id}, {@code open_id}, ...
     * @return the id of the created message
     */
    Mono<String> sendMarkdown(String receiveIdType, String receiveId, String markdown) {
        String body = requestBody(receiveId, markdown);
        return tenantToken
                .get()
                .flatMap(
                        token ->
                                client.post()
                                        .uri(
                                                b ->
                                                        b.path(SEND_PATH)
                                                                .queryParam(
                                                                        "receive_id_type",
                                                                        receiveIdType)
                                                                .build())
                                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .bodyValue(body)
                                        .exchangeToMono(
                                                response ->
                                                        response.bodyToMono(String.class)
                                                                .defaultIfEmpty("")))
                .map(FeishuCardSender::messageId);
    }

    /** The {@code content} field of a Feishu message is the card JSON as a string, not an object. */
    static String requestBody(String receiveId, String markdown) {
        ObjectNode element = MAPPER.createObjectNode();
        element.put("tag", "markdown");
        element.put("content", markdown);
        ObjectNode card = MAPPER.createObjectNode();
        card.put("schema", "2.0");
        card.putObject("body").putArray("elements").add(element);

        ObjectNode message = MAPPER.createObjectNode();
        message.put("receive_id", receiveId);
        message.put("msg_type", "interactive");
        try {
            message.put("content", MAPPER.writeValueAsString(card));
            return MAPPER.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize the Feishu card", e);
        }
    }

    private static String messageId(String response) {
        JsonNode json;
        try {
            json = MAPPER.readTree(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Feishu send returned a non-JSON body: " + response, e);
        }
        int code = json.path("code").asInt(-1);
        if (code != 0) {
            throw new IllegalStateException(
                    "Feishu send failed: code=" + code + " msg=" + json.path("msg").asText(""));
        }
        return json.path("data").path("message_id").asText("");
    }
}
