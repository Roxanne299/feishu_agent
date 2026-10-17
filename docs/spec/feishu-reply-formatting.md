# Spec：飞书回复支持 Markdown 渲染并去除模型思维链

状态：待实现
适用仓库：`Roxanne299/feishu_agent`，分支 `claude/funny-galileo-xkiub6`
写作日期：2026-10-18

---

## 1. 目标

1. **去除思维链**：模型返回的 `<think>...</think>` 内容不得出现在发给用户的回复里。
2. **Markdown 正常渲染**：回复里的粗体、列表、链接、代码块、分割线在飞书客户端里应显示为富文本，而不是原样的 `##`、`**`。

## 2. 非目标

- 不改模型、不改 prompt、不换 `MiniMax-M3`。
- 不处理流式输出 / 打字机效果。
- 不改动飞书开放平台后台配置（权限、事件、发布由人工完成）。
- 不处理安全项「密钥文件位于 Agent 工作目录内」（另行跟进，见 §10）。

---

## 3. 背景与现状

这是一个用 AgentScope Java 搭的飞书机器人：Spring Boot 4.0.4 + WebFlux，Java 17，包名 `com.example.feishuagent`。模型走 OpenAI 兼容端点配置的 MiniMax-M3。服务已部署在阿里云并正常收发消息。

### 3.1 现象一：思维链泄漏

线上真实日志（`journalctl -u feishu-agent`）：

```
Agent replied: <think>The user is greeting me in Chinese. I should respond in a
friendly, humorous way as per my persona (a humorous Feishu assistant).
Keep it short as instructed.</think>

我是飞书助手——上班帮你摸鱼，下班帮你加班 🤖
```

`<think>` 块被原样发给了用户。注意**思维链的语言不固定**，同一个机器人出现过中文和英文两种。

### 3.2 现象二：Markdown 不渲染

模型输出 `## 方案 A`、`**捷径**`、`[飞书开放平台](https://open.feishu.cn)`，用户在飞书里看到的是这些符号的字面量。

**根因已定位**：`agentscope-extensions-channel-feishu:2.0.4` 的 `FeishuOutboundClient` 把消息类型写死为 `text`。反编译其常量池可见：

```
msg_type
text
getTextContent
Message text required
/open-apis/im/v1/messages
/open-apis/im/v1/messages/{id}/reply
receive_id_type
```

没有 `interactive` 分支。飞书的 `text` 类型消息不解析 Markdown，所以无论模型输出什么都只能是纯文本。**这不是模型问题，是发送方式问题。**

---

## 4. 现有代码结构

```
src/main/java/com/example/feishuagent/
├── FeishuAgentApplication.java    @SpringBootApplication + @ConfigurationPropertiesScan
├── AgentConfig.java               构建 HarnessAgent / FeishuChannel / GatewayBootstrap 三个 Bean
├── FeishuWebhookController.java   POST /feishu/callback：验签解密 → 去重 → 先 ack 再后台跑 Agent
├── LocalChatController.java       GET /chat?q=...  本地验证用，走 gateway.chatUiChannel()
├── AgentProperties.java           @ConfigurationProperties("agent")   model, sysPrompt
├── LlmProperties.java             @ConfigurationProperties("llm")     baseUrl, apiKey, model
└── FeishuProperties.java          @ConfigurationProperties("feishu")  appId, appSecret, encryptKey, verificationToken
```

当前的回复链路在 `FeishuWebhookController.callback()` 末尾：

```java
channel.dispatch(in)
        .subscribeOn(Schedulers.boundedElastic())
        .subscribe(
                reply -> log.info("Agent replied: {}", reply.getTextContent()),
                err -> log.error("Agent run failed for event {}", eventId, err));
return ResponseEntity.ok("{}");
```

`channel.dispatch(in)` **同时做两件事**：跑 Agent，并把结果以 `text` 投递给飞书。所以无法在投递前拦截内容——这是必须改流程的原因。

---

## 5. 关键接口（已通过 `javap` 核实）

### 5.1 可以「只跑不投递」

`io.agentscope.harness.agent.gateway.Gateway` 存在两组重载：

```java
Mono<Msg> run(MsgContext ctx, List<Msg> msgs);                        // 不投递，返回回复
Mono<Msg> run(MsgContext ctx, List<Msg> msgs, OutboundAddress addr);  // 跑完自动投递
```

**用不带 `OutboundAddress` 的那个**，拿到 `Msg` 后由我们自己发送。

### 5.2 相关类型

```java
// record，7 参与 6 参两个构造器；另有 defaultContext() / withUserId(String) / canonicalKey() / channel()
io.agentscope.harness.agent.gateway.MsgContext

// record，含 senderId()、peer()、messages()、isDm()、isThread()、builder(...)
io.agentscope.harness.agent.gateway.channel.InboundMessage

// GatewayBootstrap 已作为 Bean 存在（见 AgentConfig），LocalChatController 已注入使用
io.agentscope.harness.agent.gateway.GatewayBootstrap
```

`FeishuInboundMapper` 的常量池含 `chat_type` / `GROUP` / `DIRECT` / `mentions`，**群聊消息本身已支持**，本次改动必须同时适用于单聊和群聊。

---

## 6. 方案

### 6.1 思维链剥离

新增一个独立的、可单测的工具类（建议 `ReplySanitizer`，放在同包下）：

```java
static String stripReasoning(String raw)
```

要求：

| 输入情形 | 期望输出 |
|---|---|
| `<think>推理</think>\n\n正文` | `正文` |
| 无 `<think>` 的纯正文 | 原样返回 |
| `<think>` 未闭合（流式被截断） | 从 `<think>` 起全部丢弃；若因此为空，见下一行 |
| 剥离后为空字符串 | 返回一个兜底提示（例如 `（模型没有返回内容）`），**不得返回空串**——飞书发空消息会报错 |
| 多段 `<think>...</think>` | 全部剥离 |
| 正文中间出现 `<think>` | 同样剥离该块，保留前后正文 |
| 剥离后开头残留空行 | 去掉首尾空白 |

实现约束：

- 大小写不敏感匹配标签。
- 不要用贪婪正则跨多个块匹配（`.*?` + `DOTALL`）。
- 纯字符串处理，不要引新依赖。

### 6.2 改为发送飞书卡片

新增一个发送组件（建议 `FeishuCardSender`）：

```java
Mono<Void> sendMarkdown(String receiveId, String receiveIdType, String markdown)
```

**接口**：`POST https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type={open_id|chat_id}`

**鉴权**：`Authorization: Bearer <tenant_access_token>`。token 通过
`POST /open-apis/auth/v3/tenant_access_token/internal`，body `{"app_id":..., "app_secret":...}` 获取，
取 `FeishuProperties` 里的凭证。**token 有效期 2 小时，必须缓存并在过期前刷新**，不要每条消息都换一次。

> 已验证：用当前配置调用该接口返回 `code=0`，凭证可用。

**请求体**：

```json
{
  "receive_id": "<open_id 或 chat_id>",
  "msg_type": "interactive",
  "content": "<卡片 JSON 的字符串形式>"
}
```

注意 `content` 是**字符串**，不是嵌套对象，需要把卡片 JSON 序列化后再放进去。

**卡片内容**用 `lark_md`：

```json
{
  "config": {"wide_screen_mode": true},
  "elements": [
    {"tag": "div", "text": {"tag": "lark_md", "content": "<这里放 markdown>"}}
  ]
}
```

`lark_md` 支持：`**粗体**`、`*斜体*`、`~~删除线~~`、`[文字](链接)`、`<at id=...>`、有序/无序列表、`---` 分割线、行内代码。

**不支持**的 Markdown 需要降级处理：

| Markdown | lark_md 行为 | 要求的处理 |
|---|---|---|
| `# / ## / ###` 标题 | 不渲染 | 转成 `**粗体**` 单独成行 |
| ``` 代码块 | 不渲染为块 | 保留原文；可选：转成独立的 `div` 元素 |
| 表格 | 不支持 | 保留原文（可接受） |

降级转换也应是独立可单测的函数，例如 `static String toLarkMd(String markdown)`。

**长度限制**：飞书单条消息内容有上限（卡片 JSON 序列化后约 30 KB）。超长时截断并在末尾追加 `…（内容过长已截断）`，不要让请求直接失败。

### 6.3 流程改造

`FeishuWebhookController.callback()` 末尾改为：

```java
gateway.run(ctxFrom(in), in.messages())          // 只跑，不投递
        .subscribeOn(Schedulers.boundedElastic())
        .map(msg -> ReplySanitizer.stripReasoning(msg.getTextContent()))
        .flatMap(text -> cardSender.sendMarkdown(targetId, targetType, text))
        .subscribe(
                v -> log.info("Agent replied and card sent for event {}", eventId),
                err -> log.error("Agent run or send failed for event {}", eventId, err));
return ResponseEntity.ok("{}");                   // 先 ack 的行为不变
```

**必须保留的现有行为**（回归风险点）：

1. **先 ack 再处理**。`return ResponseEntity.ok("{}")` 必须在 Agent 开始跑之前返回。飞书 3 秒不响应就重推，模型要跑 3~16 秒。这是现有设计的核心，不能退化成同步等待。
2. **事件去重**。`seenEventIds` 对 `event_id` 的去重逻辑保留。
3. **验签 / 解密 / token 校验**不动。
4. **会话记忆按人隔离**。现在不同用户的对话历史是分开的；改用 `gateway.run` 后必须保证 `MsgContext` 的构造能维持同样的隔离，**不能让所有人共用一个上下文**。

> ⚠️ 实现者必须先确认：`FeishuChannel.dispatch()` 内部是如何构造 `MsgContext` 的（`channel` / `userId` / `peer` 各字段取什么值），并在新代码里保持一致，否则会丢失历史对话或串号。`MsgContext.canonicalKey()` 是判断会话键的入口。建议先写一个临时日志打印现有 dispatch 路径下的 `canonicalKey()`，比对改造前后是否一致。

**收件人**：从 `InboundMessage` 取。单聊用发送者的 `open_id`（`receive_id_type=open_id`），群聊用 `chat_id`（`receive_id_type=chat_id`）。现有日志格式 `direct:oc_xxx` / `group:oc_xxx` 来自 `in.peer().key()`，可据此判断。

### 6.4 `/chat` 端点

`LocalChatController` 返回的 `reply` 字段也应经过 `stripReasoning`，保持两条链路一致。该端点不涉及卡片。

---

## 7. 测试要求

项目目前**没有** `src/test`，需要新建，并在 `pom.xml` 加 `spring-boot-starter-test`（版本由 `spring-boot-dependencies` BOM 管理，不要写死）。

必须覆盖：

**`ReplySanitizer`**（纯单测，无 Spring 上下文）
- 含一段 `<think>` → 正确剥离
- 不含 `<think>` → 原样
- `<think>` 未闭合 → 丢弃其后全部
- 多段 `<think>` → 全部剥离
- 全文都是 `<think>` → 返回兜底文案而非空串
- 大小写混合 `<Think>` → 同样剥离
- 剥离后首尾空白被清理

**Markdown 降级**
- `## 标题` → `**标题**`
- 链接、粗体、列表原样保留
- 超长输入被截断且带截断提示

**`FeishuCardSender`**
- 用 `MockWebServer` 或 WireMock 断言：请求 URL 带正确的 `receive_id_type`、`msg_type` 为 `interactive`、`content` 是字符串化的卡片 JSON、带 `Authorization` 头
- token 缓存：连续两次发送只触发一次 token 接口调用

---

## 8. 验收标准

1. `./mvnw clean test` 全绿。**本机默认 `mvn` 是 3.3.9 太旧跑不动，必须用仓库自带的 `./mvnw`**（已钉 3.9.9）。
2. 单聊发一条消息，飞书客户端里：
   - 看不到任何 `<think>` 内容
   - `**粗体**` 显示为粗体，链接可点击，列表有缩进
3. 群聊 @机器人 同样生效。
4. 飞书回调仍在 3 秒内收到 ack（看 nginx 访问日志，`POST /feishu/callback` 应为 `200 2`，即 2 字节的 `{}`）。
5. 同一用户连续两轮对话，第二轮能引用第一轮内容（会话记忆未丢）。
6. 模型调用失败时不发空卡片，日志有明确 error。

---

## 9. 部署与验证方式（供实现者自测）

服务跑在阿里云，SSH 别名 `aliyun`（`root@47.119.141.31`）：

```
/opt/feishu-agent/
├── feishu-agent.jar -> feishu-agent-0.1.0-SNAPSHOT.jar
├── feishu-agent.env          systemd EnvironmentFile，600，含全部密钥
├── jre/                      Temurin 17 JRE
└── .agentscope/              Agent workspace
```

systemd 单元：`/etc/systemd/system/feishu-agent.service`，服务名 `feishu-agent`。

发布流程（**所有下载和构建都在本机完成**，服务器上不联网拉依赖）：

```bash
./mvnw clean package -DskipTests
scp target/feishu-agent-0.1.0-SNAPSHOT.jar aliyun:/opt/feishu-agent/
ssh aliyun 'systemctl restart feishu-agent && journalctl -u feishu-agent -f'
```

本地冒烟（端口 8080，域名已配 DigiCert 证书直连，无 CDN）：

```bash
ssh aliyun 'curl -s --get "http://127.0.0.1:8080/chat" --data-urlencode "q=用markdown介绍下你自己"'
```

---

## 10. 实现者需自行确认的点

1. `FeishuChannel.dispatch()` 内部的 `MsgContext` 构造方式（§6.3 的回归风险点，**最重要**）。
2. `MsgContext` 的 record 分量名与顺序（`javap -p` 或 IDE 查看）。
3. 飞书卡片 JSON 的版本：本 spec 按 `config` + `elements` 的经典结构写；若接入方使用卡片 2.0（`schema: "2.0"`），按实际文档调整，核心要求「用 `lark_md` 承载正文」不变。
4. `GatewayBootstrap` 是否直接实现 `Gateway`，还是需要从中取出 `Gateway` 实例。

---

## 11. 已知的相邻问题（本次不做）

- **密钥文件在 Agent 工作目录内**：服务以 root 运行，`WorkingDirectory=/opt/feishu-agent`，而 `feishu-agent.env` 就在该目录下。Agent 具备文件工具，其思维链已出现过「我看到了环境配置，包含：飞书 app id、app secret、verification token、encrypt key」。目前未发生实际泄漏（已核验回复中无密钥片段），但机器人进入群聊后接触面扩大。建议后续把 env 移到 `/etc/feishu-agent.env` 并考虑以非 root 运行。
- **错误分支返回空 body**：所有拒绝路径都是 `ResponseEntity.status(...).build()`，飞书只能报「不是合法的json格式」，排查困难。建议改为返回 `{"code":...,"msg":"..."}`。
- **TLS 证书 2027-04-17 到期**（阿里云免费证书，6 个月），到期前需重新签发并替换 `/etc/nginx/ssl/agent.waaar.cn.{pem,key}`。
