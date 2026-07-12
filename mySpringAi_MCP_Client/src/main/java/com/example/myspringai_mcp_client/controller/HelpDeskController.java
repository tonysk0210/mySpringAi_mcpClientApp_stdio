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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Helpdesk MCP 端點，僅提供 helpdesk-ticket-mcp-server-stdio 的工具。
 * filesystem 相關功能由 {@link FileSystemMcpController} 負責，GitHub 相關功能由 {@link GithubMcpController} 負責。
 * <p>
 * 包含 elicitation 機制：當 MCP server 在 tool 執行中途需要補充資訊時，
 * 透過 SSE 把問題推給前端，使用者回覆後再由此 controller 解析並喚醒阻塞的 thread。
 */
@Slf4j
@RestController
@RequestMapping("/api/helpdesk")
public class HelpDeskController {

    private final ChatClient chatClient;

    // 僅用於 elicitation 回應解析：不帶任何 MCP tools、advisor、記憶，
    // 只負責把使用者自然語言輸入轉成 JSON，避免觸發不必要的 tool call。
    private ChatClient parserClient;

    private final PrettyLoggerAdvisor prettyLoggerAdvisor = new PrettyLoggerAdvisor();

    // 此 controller 專屬的記憶體。
    // 即使前端傳入相同的 conversationId，各 controller 查的是不同的 store，不會互相汙染。
    private final ChatMemory chatMemory = MessageWindowChatMemory.builder().build();

    // helpdesk tools，啟動時查詢一次並快取，與其他 controller 的 tools 完全獨立。
    private final ToolCallback[] tools;

    private final ElicitationSessionStore elicitationSessionStore;
    private final ElicitationSseService elicitationSseService;

    @Autowired
    public HelpDeskController(ChatClient.Builder chatClientBuilder,
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
                        你是一位 IT Helpdesk 智慧助理，負責協助使用者排除技術問題、查詢工單及建立工單。
                        
                        你只能使用 `troubleshootIssue`、`getTicketStatus`、`createTicket`。
                        系統沒有更新、取消、關閉或修改既有工單的工具，因此不得宣稱或主動提供這些功能。
                        
                        ## 工具契約
                        
                        - `troubleshootIssue(issue, username)`：
                          根據歷史 CLOSED 且有 resolution 的工單產生排障建議。
                          此工具不查詢使用者目前的工單，也不判斷是否有相似工單。
                          不限制此工具對同一問題的呼叫次數。
                        
                        - `getTicketStatus(username)`：
                          回傳該使用者所有狀態的工單。必須由你自行篩選狀態。
                          若結果是 MCP content wrapper，例如 `[{"text":"[...]"}]`，應解析 `text` 內的 JSON 陣列。
                        
                        - `createTicket(issue, username)`：
                          建立一張 OPEN 工單。Elicitation 只負責收集 priority 與 contactPhone。
                          Server 不會主動查詢相似工單，因此你必須在呼叫此工具前完成相似工單提醒流程。
                        
                        ## 簡化流程

                        1. 使用者只查詢既有工單時，呼叫 `getTicketStatus(username)` 並整理回覆。
                           這只是工單查詢，不代表使用者要建立新工單。

                        2. 使用者提出一個新的具體技術問題時，立即呼叫 `troubleshootIssue(issue, username)`。
                           以最新提出的具體問題為目前問題，不得改用對話中較早的問題。
                           排障內容只能來自工具結果，不得自行產生或擴充建議。
                           呈現排障結果後，詢問使用者問題是否已解決。

                        3. 使用者表示已解決或不需要開單時，結束目前流程。

                        4. 使用者表示問題仍未解決，或明確要求為目前問題開單時，
                           呼叫 `getTicketStatus(username)` 取得最新工單，再依下方規則提醒相似工單。
                           本次回覆只做提醒並詢問是否仍確認開單，不在同一次回覆中建立工單。

                        5. 若上一則助理回覆已完成相似工單提醒，並詢問是否仍要開單，
                           使用者明確確認後，直接呼叫 `createTicket(issue, username)`。
                           建立工單時必須使用剛才完成相似工單提醒的同一個問題。

                        6. 使用者在任何階段提出另一個新技術問題時，改處理最新問題，從步驟 2 重新開始。
                           「仍未解決」、「還是不行」、「是」、「確認」等回覆本身不是新技術問題。
                        
                        ## 開單前相似工單提醒
                        
                        - 只有 status 等於 OPEN 的工單可以參與比對。
                        - CLOSED 及其他狀態全部忽略。
                        - 只有「同一個具體設備、系統、服務或元件」，且「故障症狀相同、同義或高度相似」才視為相似。
                        - 只有相同大類、處理團隊、可能原因或彼此相關，不代表問題相似。
                        - 有相似工單：列出相似工單的 id、issue 與 status，告知這只是提醒，然後詢問是否仍確認開單。
                        - 沒有相似工單：告知未發現相似的進行中工單，然後詢問是否確認開單。
                        - 相似工單只用於提醒，絕對不得禁止或拒絕使用者建立新工單。

                        ## 工具使用原則

                        - 不限制任何工具的呼叫次數，依使用者目前意圖與上述流程選擇工具。
                        - 工具呼叫必須靜默，不得先輸出「讓我查詢」、「請稍等」等預告文字。
                        - 不得沿用早先一般查詢工單的結果，取代未解決後的相似工單提醒。
                        - 不得輸出內部比對過程、比對表或隱藏推理。
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
     * 1. 若 request 帶有 sessionId，依 sessionId 與 username 找到對應 elicitation，
     * 把本次訊息解析成補充資料後喚醒阻塞中的 thread。
     * 2. 否則走正常 LLM chat 流程（帶記憶）。
     * <p>
     * 時序（elicitation 情境）：
     * <pre>
     * [第一次 POST /api/helpdesk/chat] → LLM 呼叫 createTicket tool
     *     │
     *     ▼  server 發出 elicitation → sessionStore.register(request, username)
     *        sseService.push(username, ...) → 只把提示推給該使用者，future.get() 阻塞
     *     │
     *     │（使用者在聊天框輸入補充資料後送出）
     *     ▼
     * [第二次 POST /api/helpdesk/chat，帶 sessionId] → handleElicitationChatResponse()
     *        parserClient 解析自然語言 → sessionStore.complete() 解除阻塞
     *     ▼  立即回傳確認訊息，第一次的 POST 繼續完成 tool 並回傳最終結果
     * </pre>
     */
    @PostMapping("/chat")
    public String chat(@RequestBody ChatPayload chatPayload,
                       @RequestHeader(value = "username", required = false) String username) {
        if (chatPayload.sessionId() != null && !chatPayload.sessionId().isBlank()) {
            return handleElicitationChatResponse(chatPayload.sessionId(), username, chatPayload.message());
        }
        if (elicitationSessionStore.hasPending(username)) {
            return "目前仍有等待補充資料的請求，請先完成或取消該請求。";
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
    public SseEmitter elicitationStream(@RequestParam String username) {
        // 根據前端送來的 username 先註冊 emitter，再重送 pending session；與即時 push 競爭時最多重複、不會漏送。
        SseEmitter emitter = elicitationSseService.subscribe(username);
        elicitationSessionStore.pendingForOwner(username)
                .forEach(pending -> elicitationSseService.push(
                        username,
                        pending.sessionId(),
                        pending.serverMessage(),
                        pending.schema()));
        return emitter;
    }

    /**
     * 取消指定的 elicitation session。MCP server 收到 CANCEL 後，
     * 會依目前 createTicket 邏輯使用預設優先等級與聯絡電話繼續建立工單。
     * <p>
     * /helpdesk/chat
     * 用途：一般聊天或提交補充資料
     * 內容：需要讓後端或 LLM 解析
     * <p>
     * /elicitation/{sessionId}/cancel
     * 用途：明確取消指定 elicitation
     * 內容：不需解析，不需要 request body
     */
    @PostMapping("/elicitation/{sessionId}/cancel")
    public ResponseEntity<String> cancelElicitation(
            @PathVariable String sessionId,
            @RequestHeader("username") String username) {

        if (!elicitationSessionStore.cancel(sessionId, username)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("找不到等待中的補充資料請求，可能已完成或逾時。");
        }

        log.info("使用者取消 Elicitation sessionId={} username={}", sessionId, username);
        return ResponseEntity.ok("✅ 已取消補充資料，系統將使用預設值繼續建立工單。");
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
    private String handleElicitationChatResponse(String sessionId, String username, String userMessage) {

        // 步驟 1：依 sessionId 與 username 取出等待中的 session。
        // 若 session 已逾時或被取消，直接告知使用者。
        ElicitationSessionStore.PendingSession pending = elicitationSessionStore.findPending(sessionId, username)
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
            if (!elicitationSessionStore.complete(pending.sessionId(), username, data)) {
                return "補充資料請求已完成、取消或逾時。";
            }
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
