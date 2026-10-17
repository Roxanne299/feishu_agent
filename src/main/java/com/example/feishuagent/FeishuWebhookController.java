package com.example.feishuagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.channel.feishu.FeishuChannel;
import io.agentscope.extensions.channel.feishu.FeishuCrypto;
import io.agentscope.extensions.channel.feishu.FeishuInboundMapper;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.scheduler.Schedulers;

/**
 * Feishu event-subscription callback ({@code POST /feishu/callback}).
 *
 * <p>agentscope ships its own {@code FeishuCallbackController}, but it only returns once the agent
 * has replied. Feishu expects a 200 within 3 seconds and otherwise re-delivers the event, which
 * makes the bot answer the same message several times. This controller acks immediately, drops
 * redeliveries by {@code event_id}, and runs the agent in the background; the reply itself is
 * still sent by {@link FeishuChannel#dispatch}.
 */
@RestController
public class FeishuWebhookController {

    private static final Logger log = LoggerFactory.getLogger(FeishuWebhookController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SEEN_EVENTS_CAPACITY = 1000;

    private final FeishuChannel channel;
    private final FeishuProperties props;
    private final FeishuCrypto crypto;
    private final FeishuInboundMapper mapper = new FeishuInboundMapper(AgentConfig.FEISHU_CHANNEL_ID);
    private final Set<String> seenEventIds =
            Collections.synchronizedSet(
                    Collections.newSetFromMap(
                            new LinkedHashMap<>() {
                                @Override
                                protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                                    return size() > SEEN_EVENTS_CAPACITY;
                                }
                            }));

    public FeishuWebhookController(FeishuChannel channel, FeishuProperties props) {
        this.channel = channel;
        this.props = props;
        this.crypto = StringUtils.hasText(props.encryptKey()) ? new FeishuCrypto(props.encryptKey()) : null;
    }

    @PostMapping(value = "/feishu/callback", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> callback(
            @RequestHeader(value = "X-Lark-Signature", required = false) String signature,
            @RequestHeader(value = "X-Lark-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Lark-Request-Nonce", required = false) String nonce,
            @RequestBody String rawBody) {
        JsonNode envelope;
        try {
            if (crypto != null
                    && signature != null
                    && !crypto.verifySignature(signature, timestamp, nonce, rawBody)) {
                log.warn("Feishu callback: signature mismatch");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            envelope = MAPPER.readTree(rawBody);
            if (envelope.has("encrypt")) {
                if (crypto == null) {
                    log.warn("Feishu callback is encrypted but FEISHU_ENCRYPT_KEY is not set");
                    return ResponseEntity.badRequest().build();
                }
                envelope = MAPPER.readTree(crypto.decrypt(envelope.get("encrypt").asText()));
            }
        } catch (Exception e) {
            log.warn("Feishu callback: cannot parse body: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        // URL verification handshake when saving the callback URL in the Feishu console.
        Optional<String> challenge = FeishuInboundMapper.extractUrlChallenge(envelope);
        if (challenge.isPresent()) {
            if (!tokenMatches(envelope.path("token").asText(""))) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            log.info("Feishu URL verification OK");
            return ResponseEntity.ok(MAPPER.createObjectNode().put("challenge", challenge.get()).toString());
        }

        if (!tokenMatches(envelope.path("header").path("token").asText(""))) {
            log.warn("Feishu callback: verification token mismatch");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String eventId = envelope.path("header").path("event_id").asText("");
        if (!eventId.isEmpty() && !seenEventIds.add(eventId)) {
            log.info("Feishu callback: duplicate event {} ignored", eventId);
            return ResponseEntity.ok("{}");
        }

        Optional<InboundMessage> inbound = mapper.map(envelope);
        if (inbound.isEmpty()) {
            log.debug("Feishu callback: event {} is not a user message, ignored", eventId);
            return ResponseEntity.ok("{}");
        }
        InboundMessage in = inbound.get();
        log.info(
                "Feishu message from {} in {}: {}",
                in.senderId(),
                in.peer().key(),
                in.messages().get(0).getTextContent());

        channel.dispatch(in)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        reply -> log.info("Agent replied: {}", reply.getTextContent()),
                        err -> log.error("Agent run failed for event {}", eventId, err));
        return ResponseEntity.ok("{}");
    }

    /** No token configured means "skip the check"; otherwise it must match exactly. */
    private boolean tokenMatches(String received) {
        String expected = props.verificationToken();
        if (!StringUtils.hasText(expected)) {
            return true;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8));
    }
}
