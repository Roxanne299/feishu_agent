# feishu_agent

一个最简的 [AgentScope Java](https://github.com/agentscope-ai/agentscope-java)（2.0.4）Agent，接入飞书机器人：在飞书里给机器人发消息 → Agent（MiniMax / 通义千问 等）生成回复 → 回到飞书。

## 结构

```
飞书开放平台 ──POST /feishu/callback──▶ FeishuWebhookController
                                          │ 解密 / 校验 token / URL 验证 / event_id 去重
                                          │ 立即返回 200（飞书要求 3 秒内响应）
                                          ▼ (后台异步)
                                   FeishuChannel.dispatch()  ← agentscope-extensions-channel-feishu
                                          ▼
                              GatewayBootstrap → HarnessAgent (MiniMax-M3 / qwen-plus ...)
                                          ▼
                     FeishuChannel 用 tenant_access_token 调 /open-apis/im/v1/messages 回复
```

| 文件 | 作用 |
|------|------|
| `AgentConfig.java` | 创建 `HarnessAgent`、`FeishuChannel`、`GatewayBootstrap` |
| `FeishuWebhookController.java` | 飞书事件回调入口 |
| `LocalChatController.java` | `GET /chat?q=...`，不经过飞书直接测 Agent |

> 为什么不用 agentscope 自带的 `FeishuCallbackController`？它要等 Agent 回复完才返回 HTTP 200，大模型一般超过 3 秒，飞书会判定推送失败并重推，导致同一条消息被回复多次。这里自己写了一个：先 ack，再后台跑 Agent；解析、解密、发回复仍然复用 agentscope 的飞书模块。

## 1. 飞书后台配置

1. 打开 [飞书开放平台](https://open.feishu.cn/app) → 创建**企业自建应用**。
2. **添加应用能力** → 机器人。
3. **权限管理** 开通：
   - `im:message`（获取与发送单聊、群组消息）
   - `im:message.p2p_msg:readonly`（读取用户发给机器人的单聊消息）
   - `im:message.group_at_msg:readonly`（接收群聊中 @机器人消息，可选）
4. **凭证与基础信息**：记下 App ID、App Secret。
5. **事件与回调 → 加密策略**：记下 Verification Token（Encrypt Key 可选，配置了就也填上）。
6. 先把下面第 2 步的服务跑起来，然后 **事件与回调 → 事件配置**：
   - 订阅方式选「将事件发送至开发者服务器」，请求地址填 `https://<你的公网地址>/feishu/callback`，保存时飞书会做一次 URL 验证。
   - 添加事件 `接收消息 im.message.receive_v1`。
7. **版本管理与发布** → 创建版本并发布（权限和事件要发布后才生效）。

本地开发没有公网地址的话，可以用 `ngrok http 8080`、`cpolar` 之类的内网穿透工具。

## 2. 运行

需要 JDK 17+、Maven。Mac 上可以 `brew install openjdk@21 maven`。

### 方式一：配置文件（推荐本地使用）

```bash
cp config/application.yml.example config/application.yml
# 编辑 config/application.yml，填入 MiniMax key 和飞书 App ID / Secret / Verification Token
mvn spring-boot:run
```

`config/application.yml` 已在 `.gitignore` 里，不会被提交。需要在项目根目录下启动，Spring Boot 才会读取它。

### 方式二：环境变量

#### 模型配置（二选一）

**A. MiniMax 或其他 OpenAI 兼容接口**（DeepSeek、Kimi、硅基流动等同理，换 base url 和模型名即可）：

```bash
export LLM_BASE_URL=https://api.minimaxi.com/v1   # 国内站；海外站用 https://api.minimax.io/v1
export LLM_API_KEY=你的MiniMax_API_Key
export LLM_MODEL=MiniMax-M3                       # 以 MiniMax 控制台里可用的模型名为准
```

**B. AgentScope 内置厂商**（不设置 `LLM_BASE_URL` 时生效）：

```bash
export DASHSCOPE_API_KEY=sk-xxx                   # 阿里云百炼
export AGENT_MODEL=dashscope:qwen-plus            # 默认值
```

#### 飞书配置并启动

```bash
export FEISHU_APP_ID=cli_xxx
export FEISHU_APP_SECRET=xxx
export FEISHU_VERIFICATION_TOKEN=xxx         # 强烈建议配置，用于校验请求确实来自飞书
# export FEISHU_ENCRYPT_KEY=xxx              # 飞书后台开启了加密才需要

mvn spring-boot:run
# 或：mvn package && java -jar target/feishu-agent-0.1.0-SNAPSHOT.jar
```

启动后日志里出现 `PRE_REASONING | model=MiniMax-M3` 即说明用的是你配置的模型。

## 3. 验证

先单独验证 Agent 和模型是通的：

```bash
curl 'http://localhost:8080/chat?q=你好'
# {"reply":"你好！有什么可以帮你的？"}
```

模拟一次飞书的 URL 验证：

```bash
curl -XPOST localhost:8080/feishu/callback -H 'Content-Type: application/json' \
  -d '{"type":"url_verification","challenge":"abc","token":"<你的 Verification Token>"}'
# {"challenge":"abc"}
```

最后在飞书里搜索你的机器人，私聊发一句话（或在群里 @它），控制台会打印：

```
Feishu message from ou_xxx in direct:oc_xxx: 你好
Agent replied: ...
```

同时机器人会在飞书里回复。

## 说明 / 限制

- 目前只处理文本消息（agentscope 飞书模块的限制）。
- 会话按飞书 `chat_id` 区分，同一个单聊/群聊共享上下文。
- 去重记录在内存中，单实例够用；多实例部署需要换成共享存储。
- 运行时会在 `.agentscope/workspace` 下生成 Agent 工作目录（已 gitignore）。在里面放一个 `AGENTS.md` 可以定义 Agent 的人设，不放也能跑，只是启动时会有一条 WARN。
