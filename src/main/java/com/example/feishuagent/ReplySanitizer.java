package com.example.feishuagent;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Turns a raw model reply into text that is safe to show in Feishu: no reasoning, never empty,
 * never over the size Feishu accepts.
 *
 * <p>MiniMax-M3 returns its chain of thought inline in the message content as {@code
 * <think>...</think>} (providers that use a separate {@code reasoning_content} field are already
 * stripped by agentscope, which keeps only text blocks). The language of the block is not fixed.
 */
final class ReplySanitizer {

    static final String EMPTY_REPLY = "（模型没有返回内容）";
    static final String TRUNCATED_SUFFIX = "…（内容过长已截断）";

    /** Feishu rejects a card request body over 30 KB; leave room for the card JSON around the text. */
    private static final int MAX_TEXT_BYTES = 20_000;

    private static final Pattern CLOSED_THINKING =
            Pattern.compile(
                    "<\\s*(think|thinking)\\s*>.*?<\\s*/\\s*\\1\\s*>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern UNCLOSED_THINKING =
            Pattern.compile(
                    "<\\s*(think|thinking)\\s*>.*$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private ReplySanitizer() {}

    /** Drops {@code <think>} blocks, including an unclosed one left by a truncated response. */
    static String stripThinking(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = CLOSED_THINKING.matcher(raw).replaceAll("");
        text = UNCLOSED_THINKING.matcher(text).replaceAll("");
        return text.strip();
    }

    /** The markdown to put in a card. Feishu rejects an empty message, so empty becomes a notice. */
    static String cardText(String raw) {
        String text = stripThinking(raw);
        return text.isEmpty() ? EMPTY_REPLY : truncate(text);
    }

    private static String truncate(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_TEXT_BYTES) {
            return text;
        }
        int cut = MAX_TEXT_BYTES - TRUNCATED_SUFFIX.getBytes(StandardCharsets.UTF_8).length;
        // Never cut inside a multi-byte character: 10xxxxxx is a continuation byte.
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
            cut--;
        }
        return new String(bytes, 0, cut, StandardCharsets.UTF_8).stripTrailing() + TRUNCATED_SUFFIX;
    }
}
