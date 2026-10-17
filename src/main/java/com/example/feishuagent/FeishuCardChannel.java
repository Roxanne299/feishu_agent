package com.example.feishuagent;

import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.gateway.Gateway;
import io.agentscope.harness.agent.gateway.channel.Channel;
import io.agentscope.harness.agent.gateway.channel.ChannelConfig;
import io.agentscope.harness.agent.gateway.channel.ChannelRouter;
import io.agentscope.harness.agent.gateway.channel.InboundMessage;
import io.agentscope.harness.agent.gateway.channel.OutboundAddress;
import io.agentscope.harness.agent.gateway.channel.RouteResult;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Feishu channel that replies with a card instead of a text message.
 *
 * <p>Drop-in replacement for {@code FeishuChannel}: it routes an inbound message with the same
 * {@link ChannelRouter}, so the {@code MsgContext} — and with it the per-chat conversation memory —
 * is built exactly as before, runs the agent through the gateway, and then sends the reply itself
 * through {@link FeishuCardSender} rather than through agentscope's text-only outbound client.
 * {@code gateway.run(.., OutboundAddress, ..)} does not deliver anything; it only records the route
 * so the agent can reach the chat later.
 *
 * <p>Inbound events are still decrypted and mapped by {@code FeishuWebhookController}.
 */
class FeishuCardChannel implements Channel {

    private static final Logger log = LoggerFactory.getLogger(FeishuCardChannel.class);

    /**
     * agentscope's inbound mapper puts the Feishu {@code chat_id} in the peer for direct chats as
     * well as groups (hence the {@code direct:oc_...} in the logs), and its outbound client always
     * sends with {@code receive_id_type=chat_id}. Keep that, so replies land in the same chat.
     */
    private static final String RECEIVE_ID_TYPE = "chat_id";

    private final ChannelConfig config;
    private final ChannelRouter router;
    private final FeishuCardSender sender;
    private volatile Gateway gateway;

    FeishuCardChannel(ChannelConfig config, FeishuCardSender sender) {
        this.config = config;
        this.router = new ChannelRouter(config.defaultAgentId());
        this.sender = sender;
    }

    @Override
    public String channelId() {
        return config.channelId();
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public void init(Gateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public Mono<Msg> dispatch(InboundMessage in) {
        Gateway gw = this.gateway;
        if (gw == null) {
            return Mono.error(new IllegalStateException("Channel " + channelId() + " has no gateway"));
        }
        RouteResult route = router.resolveRoute(config, in);
        return gw.run(
                        route.context(),
                        in.messages(),
                        route.outboundAddress(),
                        in.runtimeContext(),
                        in)
                .flatMap(reply -> send(in.peer().id(), reply).thenReturn(reply));
    }

    /** Proactive messages from the agent (wakeups, subagents) get the same card treatment. */
    @Override
    public void deliver(OutboundAddress address, List<Msg> msgs) {
        String receiveId = receiveId(address);
        Flux.fromIterable(msgs)
                .concatMap(msg -> send(receiveId, msg))
                .subscribe(
                        null,
                        err -> log.error("Feishu delivery to {} failed", receiveId, err));
    }

    private Mono<Void> send(String receiveId, Msg msg) {
        String markdown = ReplySanitizer.cardText(msg.getTextContent());
        return sender.sendMarkdown(RECEIVE_ID_TYPE, receiveId, markdown)
                .doOnNext(messageId -> log.info("Feishu card sent to {}: {}", receiveId, messageId))
                .doOnError(err -> log.error("Feishu card send to {} failed", receiveId, err))
                .then();
    }

    /** {@code OutboundAddress.to()} is {@code channelId:KIND:peerId}. */
    private static String receiveId(OutboundAddress address) {
        String to = address.to();
        return to.substring(to.lastIndexOf(':') + 1);
    }
}
