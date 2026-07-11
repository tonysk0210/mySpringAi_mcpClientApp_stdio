package com.example.myspringai_mcp_client.controller;

import com.example.myspringai_mcp_client.advisor.PrettyLoggerAdvisor;
import com.example.myspringai_mcp_client.advisor.TokenUsageAuditAdvisor;
import com.example.myspringai_mcp_client.payload.ChatPayload;
import com.example.myspringai_mcp_client.util.ElicitationSessionStore;
import com.example.myspringai_mcp_client.util.ElicitationSseService;
import com.example.myspringai_mcp_client.util.ToolUtil;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Helpdesk MCP 端點，僅提供 helpdesk-ticket-mcp-server-stdio 的工具。
 * filesystem / GitHub 相關功能由 {@link McpClientController} 負責。
 * <p>
 * 包含 elicitation 機制：當 MCP server 在 tool 執行中途需要補充資訊時，
 * 透過 SSE 把問題推給前端，使用者回覆後再由此 controller 解析並喚醒阻塞的 thread。
 */
@Slf4j
@RestController
@RequestMapping("/api/helpdesk")
public class McpHelpdeskController {

    private final ChatClient chatClient;

    // 僅用於 elicitation 回應解析：不帶任何 MCP tools、advisor、記憶，
    // 只負責把使用者自然語言輸入轉成 JSON，避免觸發不必要的 tool call。
    private ChatClient parserClient;

    private final PrettyLoggerAdvisor prettyLoggerAdvisor = new PrettyLoggerAdvisor();

    // 此 controller 專屬的記憶體，與 McpClientController 的 chatMemory 完全隔離。
    // 即使前端傳入相同的 conversationId，兩個 controller 查的是不同的 store，不會互相汙染。
    private final ChatMemory chatMemory = MessageWindowChatMemory.builder().build();

    // helpdesk tools，啟動時查詢一次並快取，與其他 controller 的 tools 完全獨立。
    private final ToolCallback[] tools;

    private final ElicitationSessionStore elicitationSessionStore;
    private final ElicitationSseService elicitationSseService;

    @Autowired
    public McpHelpdeskController(ChatClient.Builder chatClientBuilder,
                                 ChatClient.Builder parserClientBuilder,
                                 List<McpSyncClient> mcpClients,
                                 ElicitationSessionStore elicitationSessionStore,
                                 ElicitationSseService elicitationSseService) {

        // 使用 server 在 MCP 協定層回報的實際名稱（getServerInfo().name() = "mySpringAi_MCP_Server_stdio"）做比對，
        // 注意：這個名稱來自 server 本身的 spring.application.name，不是 application.properties 的 connection key。
        this.tools = ToolUtil.selectToolsFor(mcpClients, "mySpringAi_MCP_Server_stdio", null);

        // ChatClient.Builder 是 prototype scope，每個 constructor parameter 各自注入獨立實例，
        // parserClientBuilder 與 chatClientBuilder 完全隔離，不共用任何內部狀態。
        this.parserClient = parserClientBuilder.build();

        this.chatClient = chatClientBuilder
                .defaultSystem("""
                        回答時請使用清楚、易理解且專業的繁體中文。
                        你是一位 IT Helpdesk 智慧助理，負責協助使用者排除技術問題並管理服務工單。

                        ## 工具說明

                        - `troubleshootIssue`：查詢全公司歷史解決案例知識庫，回傳排障建議。結果僅反映知識庫是否有對應解法，與使用者是否已有工單完全無關。
                        - `getTicketStatus`：查詢使用者目前所有未關閉工單（OPEN / IN_PROGRESS）。資料來源與 `troubleshootIssue` 獨立，是開單前必須執行的重複確認步驟。
                        - `createTicket`：建立新工單。工具會自動透過 elicitation 收集優先等級與聯絡電話，使用者拒絕提供則以預設值（MEDIUM、無電話）建立。

                        ## 處理流程

                        ### 路徑 A：知識庫有排障建議（共三個回合）

                        **回合 A1**：收到技術問題 → 呼叫 `troubleshootIssue` → 將排障步驟完整呈現，詢問「是否已依建議操作、問題是否解決」

                        **回合 A2**：使用者確認排障無效或主動要求開單 → 呼叫 `getTicketStatus`，根據結果：
                        - 有相似工單（OPEN / IN_PROGRESS）→ 告知使用者，列出既有工單，**流程結束**
                        - 無相似工單 → 回覆工單清單並說明「未發現重複工單」，詢問是否確認開立新工單
                        - **此回合不得呼叫 `createTicket`**，須等待使用者明確確認

                        **回合 A3**：使用者確認開單 → 直接呼叫 `createTicket`

                        ---

                        ### 路徑 B：知識庫無對應案例（共兩個回合）

                        **回合 B1**：收到技術問題 → 呼叫 `troubleshootIssue`，得知無對應案例 → **立即接著呼叫 `getTicketStatus`**，根據結果：
                        - 有相似工單（OPEN / IN_PROGRESS）→ 告知使用者知識庫無解法且已有相關工單，列出工單，**流程結束**
                        - 無相似工單 → 告知使用者知識庫無對應解法、也未發現重複工單，詢問「是否需要為您開立新工單」
                        - **此回合不得呼叫 `createTicket`**，須等待使用者明確確認

                        **回合 B2**：使用者確認開單 → 直接呼叫 `createTicket`

                        ## 核心規則

                        **重複工單判斷**：以**具體症狀**為準，而非大類別。判斷問題「是否相同」時，問自己：這兩個問題的使用者體驗症狀是否一樣？只有症狀本質相同（如同樣是「無法登入」）才算重複；同屬一個大類別但症狀不同，應視為不同問題，各自開立工單。唯一阻擋開單的條件是「同症狀已有 OPEN 或 IN_PROGRESS 的工單」，使用者同時擁有多張針對不同症狀的工單是正常的。

                        **工具呼叫紀律**：直接發出工具呼叫，不得先輸出任何預告文字。工具完成後再根據結果回覆。

                        **排障紀律**：排障內容必須來自 `troubleshootIssue` 的結果，不得自行推論。排障結果出來後，只詢問使用者是否已嘗試，不得主動建議開單。
                        """)
                .defaultAdvisors(
                        new TokenUsageAuditAdvisor(),
                        this.prettyLoggerAdvisor,
                        // MessageChatMemoryAdvisor 負責把對話歷史注入每次 LLM 請求，
                        // 並在收到回覆後把新的訊息存回 chatMemory。
                        // conversationId 為必要欄位，每次 request 透過 advisors param 傳入。
                        MessageChatMemoryAdvisor.builder(this.chatMemory).build())
                .build();

        this.elicitationSessionStore = elicitationSessionStore;
        this.elicitationSseService = elicitationSseService;
    }

    /**
     * Helpdesk 聊天端點，具備對話記憶，支援多輪的排障 → 確認 → 開單流程。
     * <p>
     * 流程：
     * 1. 若 MCP server 正等待使用者補充資料（elicitation pending），
     *    把本次訊息視為 elicitation 回應，解析後喚醒阻塞中的 thread。
     * 2. 否則走正常 LLM chat 流程（帶記憶）。
     * <p>
     * 時序（elicitation 情境）：
     * <pre>
     * [第一次 POST /api/helpdesk/chat] → LLM 呼叫 createTicket tool
     *     │
     *     ▼  server 發出 elicitation → sessionStore.register()，hasPending()=true
     *        sseService.push() → SSE 把提示推給前端，future.get() 阻塞
     *     │
     *     │（使用者在聊天框輸入補充資料後送出）
     *     ▼
     * [第二次 POST /api/helpdesk/chat] → hasPending()=true → handleElicitationChatResponse()
     *        parserClient 解析自然語言 → sessionStore.complete() 解除阻塞
     *     ▼  立即回傳確認訊息，第一次的 POST 繼續完成 tool 並回傳最終結果
     * </pre>
     */
    @PostMapping("/chat")
    public String chat(@RequestBody ChatPayload chatPayload,
                       @RequestHeader(value = "username", required = false) String username) {
        if (elicitationSessionStore.hasPending()) {
            return handleElicitationChatResponse(chatPayload.message());
        }

        prettyLoggerAdvisor.reset();

        return chatClient.prompt()
                .user(chatPayload.message() + ". 我的 username 是 " + username)
                .tools(tools)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, username))
                .toolContext(Map.of("progressToken", UUID.randomUUID().toString()))
                .call().content();
    }

    /**
     * SSE 長連線訂閱端點。瀏覽器訂閱後平時沉默，
     * 一旦 MCP server 發出 elicitation，立即透過此連線把提示推到聊天框。
     * <p>
     * 前端範例：
     * <pre>
     * const es = new EventSource('/api/helpdesk/elicitation/stream');
     * es.addEventListener('elicitation', e => {
     *   const { prompt } = JSON.parse(e.data);
     *   chatBox.appendBotMessage('⚠️ ' + prompt);
     * });
     * </pre>
     */
    @GetMapping(value = "/elicitation/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter elicitationStream() {
        return elicitationSseService.subscribe();
    }

    // //////////////////////////////////////////
    // Private helpers
    // //////////////////////////////////////////

    /**
     * 把使用者在聊天框輸入的自然語言解析成 elicitation 所需的 JSON，
     * 再呼叫 ElicitationSessionStore.complete() 解除阻塞中的 thread。
     * <p>
     * 使用 parserClient（不帶 MCP tools、無記憶）讓 LLM 對照 server 原始提示萃取欄位值，
     * .entity(Map.class) 讓 Spring AI 直接把 JSON 反序列化成 Map，省去手動解析。
     */
    private String handleElicitationChatResponse(String userMessage) {

        // 步驟 1：取出目前等待中的 session。
        // 若 session 已逾時或被取消，直接告知使用者。
        ElicitationSessionStore.PendingSession pending = elicitationSessionStore.firstPending()
                .orElse(null);
        if (pending == null) return "沒有待處理的補充資料請求。";

        log.info("偵測到 pending elicitation，嘗試解析使用者輸入 sessionId={}", pending.sessionId());

        try {
            // 步驟 2：用 parserClient 解析使用者輸入。
            // prompt 同時提供兩個上下文：
            //   - pending.serverMessage()：server 的原始提示，說明需要哪些欄位（例如：「請選擇優先等級與聯絡電話」）
            //   - userMessage：使用者的自然語言回應（例如：「HIGH，0912-345-678」）
            @SuppressWarnings("unchecked") // .entity(Map.class) 回傳 raw Map，此轉型由 LLM 回傳格式保證安全
            Map<String, Object> data = parserClient.prompt()
                    .user("""
                            Server 向使用者要求補充的資料說明如下：
                            「%s」

                            使用者的回應為：
                            「%s」

                            請根據以上資訊，從使用者回應中萃取所需欄位，只回傳純 JSON，不要有任何其他文字。
                            範例格式：{"priority":"HIGH","contactPhone":"+886-2-1234-5678"}
                            """.formatted(pending.serverMessage(), userMessage))
                    .call().entity(Map.class);

            // 步驟 3：complete() 會喚醒 HelpDeskElicitationProvider 中阻塞在 future.get() 的 thread，
            // 讓 server 拿到資料後繼續完成 tool 執行。
            elicitationSessionStore.complete(pending.sessionId(), data);
            log.info("Elicitation session 已完成 sessionId={} data={}", pending.sessionId(), data);

            // 步驟 4：立即回傳確認訊息給本次 POST（前端聊天框）。
            // tool 的最終結果會透過第一次仍在阻塞的 POST 回傳，不需在此等待。
            return "✅ 資料已收到，正在繼續處理，請稍候...";

        } catch (Exception e) {
            // 解析失敗時，session 維持 pending，使用者可重新輸入。
            log.warn("無法解析使用者的 elicitation 回應", e);
            return "❌ 無法解析您的輸入，請重新輸入（例如：HIGH，+886-2-1234-5678）。";
        }
    }

}
