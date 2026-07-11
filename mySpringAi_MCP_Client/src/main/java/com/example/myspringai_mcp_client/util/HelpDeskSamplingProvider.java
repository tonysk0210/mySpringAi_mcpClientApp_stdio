package com.example.myspringai_mcp_client.util;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.annotation.McpSampling;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 處理 MCP sampling（取樣）請求的 client 端 handler。
 * <p>
 * 什麼是 sampling：
 * MCP server 在執行 tool 的過程中，可以呼叫 ctx.sample(...) 把 LLM 補全請求
 * 委託給 client 端執行。Server 不需要持有 API key，借用 client 的 LLM 能力。
 * <p>
 * 完整互動流程：
 * <p>
 * MCP server 執行 tool，呼叫 ctx.sample(systemPrompt, userMessage)
 * │
 * ▼  封裝成 CreateMessageRequest，透過 MCP protocol 送給 client
 *
 * @McpSampling handler → handleSamplingRequest(request) 被呼叫
 * │
 * ├─ 1. 把 request 裡的 systemPrompt 轉成 SystemMessage
 * ├─ 2. 把 role=USER 的訊息合併成 UserMessage
 * └─ 3. 用 ChatModel 呼叫 LLM，取得生成文字
 * │
 * ▼  包成 CreateMessageResult（role=ASSISTANT）回傳給 server
 * Server 端的 ctx.sample() 返回，取得 result.content()（即 LLM 生成的文字）
 * <p>
 * 為什麼注入 ChatModel 而不是 ChatClient：
 * ChatClient 已透過 defaultTools() 綁定了 MCP tools。
 * 若在此處使用，LLM 可能再次呼叫 MCP tool → tool 再次發出 sampling → 無限循環。
 * ChatModel 是不帶任何 tools 的純 LLM 呼叫層，可安全使用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HelpDeskSamplingProvider {

    private final ChatModel chatModel;

    // @McpSampling(clients = ...) 指定只接收 helpdesk server 的 sampling，避免誤攔截其他 server 的請求。
    @McpSampling(clients = "helpdesk-ticket-mcp-server-stdio")
    public McpSchema.CreateMessageResult handleSamplingRequest(McpSchema.CreateMessageRequest request) {

        log.info("收到來自伺服器的 MCP 取樣請求。系統提示詞：{}", request.systemPrompt());

        List<Message> messages = new ArrayList<>();

        // 步驟 1：若有 systemPrompt，加入 SystemMessage 作為 LLM 的角色指令。
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            messages.add(new SystemMessage(request.systemPrompt()));
        }

        // 步驟 2：從 request.messages() 篩出 role=USER 且內容為文字的訊息，
        // 合併成單一字串後包成 UserMessage 傳給 LLM。
        // MCP 訊息格式支援多種 content 型別，這裡只取 TextContent。
        String userText = request.messages().stream()
                .filter(m -> m.content() instanceof McpSchema.TextContent
                        && m.role().name().equalsIgnoreCase(McpSchema.Role.USER.name()))
                .map(m -> ((McpSchema.TextContent) m.content()).text())
                .collect(Collectors.joining("\n"));

        log.info("取樣請求的使用者訊息（含歷史案例）：{}", userText);
        messages.add(new UserMessage(userText));

        /**
         * Message 是一個 interface，SystemMessage 和 UserMessage 是不同的實作類別，每個類別內部已經帶有自己的 role 資訊：
         *
         *   SystemMessage("你是客服助理")   // 內部 role = SYSTEM
         *   UserMessage("幫我建一張 ticket") // 內部 role = USER
         *
         *   當 chatModel.call(new Prompt(messages)) 被呼叫時，Spring AI 把 List<Message> 轉成 OpenAI API 的請求格式，每個 Message 物件的 role 會被正確對應：
         *
         *   {
         *     "messages": [
         *       { "role": "system",  "content": "你是客服助理" },
         *       { "role": "user",    "content": "幫我建一張 ticket" }
         *     ]
         *   }
         */

        // 步驟 3：用 ChatModel（不帶 MCP tools）直接呼叫 LLM。
        // 刻意不使用 ChatClient，避免 sampling → tool call → sampling 無限循環。
        ChatResponse response = chatModel.call(new Prompt(messages));
        if (response.getResult() == null) {
            throw new IllegalStateException("LLM 未針對此 MCP 取樣請求回傳任何結果");
        }

        // 步驟 4：取出生成文字與模型名稱，包成 CreateMessageResult 回傳給 server。
        // Server 端透過 result.content() 取得文字，result.model() 取得模型名稱。
        String generatedText = Objects.requireNonNullElse(response.getResult().getOutput().getText(), "");
        String model = response.getMetadata().getModel();

        log.info("LLM 使用模型 '{}' 產生了 Sampling 回應：{}", model, generatedText);

        // 步驟 5：包成 CreateMessageResult 回傳給 server。
        return McpSchema.CreateMessageResult.builder(McpSchema.Role.ASSISTANT, generatedText, model)
                .build();
    }
}
