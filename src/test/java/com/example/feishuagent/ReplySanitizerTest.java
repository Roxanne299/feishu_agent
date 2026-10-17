package com.example.feishuagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ReplySanitizerTest {

    @Test
    void dropsOneThinkingBlock() {
        String raw = "<think>The user is greeting me in Chinese.</think>\n\n我是飞书助手";
        assertThat(ReplySanitizer.stripThinking(raw)).isEqualTo("我是飞书助手");
    }

    @Test
    void keepsAReplyWithoutThinking() {
        assertThat(ReplySanitizer.stripThinking("**粗体** 和 [链接](https://open.feishu.cn)"))
                .isEqualTo("**粗体** 和 [链接](https://open.feishu.cn)");
    }

    @Test
    void dropsEverythingAfterAnUnclosedTag() {
        assertThat(ReplySanitizer.stripThinking("正文\n<think>推理被截断了")).isEqualTo("正文");
    }

    @Test
    void dropsEveryThinkingBlock() {
        String raw = "<think>一</think>正文甲<think>二</think>正文乙";
        assertThat(ReplySanitizer.stripThinking(raw)).isEqualTo("正文甲正文乙");
    }

    @Test
    void isCaseInsensitive() {
        assertThat(ReplySanitizer.stripThinking("<Think>推理</THINK>正文")).isEqualTo("正文");
        assertThat(ReplySanitizer.stripThinking("<thinking>推理</thinking>正文")).isEqualTo("正文");
    }

    @Test
    void stripsSurroundingWhitespace() {
        assertThat(ReplySanitizer.stripThinking("\n\n正文\n\n")).isEqualTo("正文");
    }

    @Test
    void cardTextFallsBackWhenNothingIsLeft() {
        assertThat(ReplySanitizer.cardText("<think>只有推理</think>")).isEqualTo(ReplySanitizer.EMPTY_REPLY);
        assertThat(ReplySanitizer.cardText("")).isEqualTo(ReplySanitizer.EMPTY_REPLY);
        assertThat(ReplySanitizer.cardText(null)).isEqualTo(ReplySanitizer.EMPTY_REPLY);
    }

    @Test
    void cardTextTruncatesAnOversizedReply() {
        String text = ReplySanitizer.cardText("飞".repeat(20_000));

        assertThat(text).endsWith(ReplySanitizer.TRUNCATED_SUFFIX);
        assertThat(text.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(20_000);
        // Truncating by bytes must not leave a broken character behind.
        assertThat(text).doesNotContain("�");
    }

    @Test
    void cardTextKeepsMarkdownAsIs() {
        String markdown = "## 方案 A\n\n- **捷径**\n- [开放平台](https://open.feishu.cn)\n\n---\n`code`";
        assertThat(ReplySanitizer.cardText(markdown)).isEqualTo(markdown);
    }
}
