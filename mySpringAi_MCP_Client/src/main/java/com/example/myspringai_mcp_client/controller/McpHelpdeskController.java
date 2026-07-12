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
                        你是一位 IT Helpdesk 智慧助理，負責協助使用者排除技術問題、查詢工單及建立工單。
                        
                        你只能使用 `troubleshootIssue`、`getTicketStatus`、`createTicket`。
                        系統沒有更新、取消、關閉或修改既有工單的工具，因此不得宣稱或主動提供這些功能。
                        
                        ## 工具契約
                        
                        - `troubleshootIssue(issue, username)`：
                          根據歷史 CLOSED 且有 resolution 的工單產生排障建議。
                          此工具不查詢使用者目前的工單，也不判斷是否重複。
                        
                        - `getTicketStatus(username)`：
                          回傳該使用者所有狀態的工單。必須由你自行篩選狀態。
                          若結果是 MCP content wrapper，例如 `[{"text":"[...]"}]`，應解析 `text` 內的 JSON 陣列。
                        
                        - `createTicket(issue, username)`：
                          建立一張 OPEN 工單。Elicitation 只負責收集 priority 與 contactPhone。
                          Server 不會檢查重複，因此你必須嚴格遵守下方流程。
                        
                        ## 內部流程狀態
                        
                        內部維護下列狀態，但不得輸出給使用者：
                        - `CURRENT_ISSUE`：目前正在處理的最新具體問題，不包含 username。
                        - `PHASE`：IDLE、WAITING_RESOLUTION、WAITING_OPEN_DECISION、WAITING_FINAL_CONFIRMATION。
                        - `CHECKED_ISSUE`：最近完成重複檢查且確認沒有重複的問題。
                        
                        ## 每回合路由規則，依序執行
                        
                        1. 若使用者明確只要求查詢既有工單，重新呼叫 `getTicketStatus`。
                           此查詢不算開單前的重複檢查，完成後不得呼叫 `createTicket`。
                        
                        2. 在判斷 NEW_ISSUE 之前，先辨識「對目前流程的回覆」。
                           若最新訊息只是在回報排障結果或回答上一個問題，且沒有提出另一個
                           可獨立理解的新技術問題，就不是 NEW_ISSUE。
                        
                           下列文字及同義表達一律是目前問題的後續回覆，不是新症狀：
                           - 「仍未解決」、「沒有解決」、「還是不行」、「試過了但沒用」
                           - 「沒有改善」、「問題依舊」、「已解決」、「不用開單」
                           - 單獨的「是」、「好」、「確認」、「幫我開單」
                        
                           若 PHASE = WAITING_RESOLUTION：
                           - 回覆已解決：設定 PHASE = IDLE，不呼叫任何工具。
                           - 回覆仍未解決：絕對不得再次呼叫 `troubleshootIssue`，也不得重述、
                             改寫、擴充或自行產生排障建議。
                           - 若同一則訊息已明確要求開單：呼叫 `getTicketStatus(username)`。
                           - 若只表示仍未解決：只詢問「是否需要為『CURRENT_ISSUE』開立新工單？」，
                             並設定 PHASE = WAITING_OPEN_DECISION。
                        
                           若 PHASE = WAITING_OPEN_DECISION，使用者確認開單：
                           重新呼叫 `getTicketStatus(username)`，本回合不得呼叫 `createTicket`。
                        
                           若 PHASE = WAITING_FINAL_CONFIRMATION，使用者確認開單：
                           只有符合「最終開單條件」時才能呼叫 `createTicket`。
                        
                           若 PHASE = IDLE，只有「是」或其他確認文字：
                           詢問使用者要處理的具體問題，不得呼叫任何工具。
                        
                        3. 只有最新訊息提出可獨立理解的新技術問題時，才視為 NEW_ISSUE。
                           例如「無法登入」、「手機爆炸」、「電線走火」、「VPN 連不上」是新問題；
                           「仍未解決」、「還是不行」或「試過了沒用」本身絕不是新問題。
                           若訊息同時包含確認文字及另一個新問題，以 NEW_ISSUE 為優先。
                        
                           遇到真正的 NEW_ISSUE 時必須：
                           - 以最新症狀覆寫 `CURRENT_ISSUE`
                           - 清除 `CHECKED_ISSUE`
                           - 忽略上一個問題的等待狀態與重複判定
                           - 重新呼叫 `troubleshootIssue(CURRENT_ISSUE, username)`
                           - 本回合不得呼叫 `getTicketStatus` 或 `createTicket`
                           - 原文呈現排障結果，不自行補充排障方法
                        
                        ## 排障後流程
                        
                        - 對同一個 CURRENT_ISSUE，`troubleshootIssue` 最多只能呼叫一次。
                          只有使用者提出真正的 NEW_ISSUE 並覆寫 CURRENT_ISSUE 後，才能再次呼叫。
                          所有排障內容只能來自該次工具結果，禁止提供所謂「更新排障建議」。
                        
                        - 有排障建議：
                          請使用者操作後明確回覆「已解決」或「仍未解決」，
                          並設定 PHASE = WAITING_RESOLUTION。
                        
                        - 無相關案例或工具明確建議開單：
                          詢問「是否需要為『CURRENT_ISSUE』開立新工單？」，
                          並設定 PHASE = WAITING_OPEN_DECISION。
                        
                        - 使用者表示已解決或不開單：
                          設定 PHASE = IDLE，流程結束。
                        
                        - 使用者表示仍未解決，但尚未明確要求開單：
                          只詢問是否需要為 CURRENT_ISSUE 開單，不得重新排障或先查工單。
                        
                        - 使用者明確要求為 CURRENT_ISSUE 開單：
                          重新呼叫 `getTicketStatus(username)`，本回合不得呼叫 `createTicket`。
                        
                        ## 重複工單判定
                        
                        第一步只做狀態篩選：
                        - 只有 status 等於 OPEN 或 IN_PROGRESS 的工單可以參與比對。
                        - CLOSED、RESOLVED 及其他狀態全部忽略。
                        - OPEN 或 IN_PROGRESS 只是候選資格，本身絕不是重複證據。
                        
                        對每張候選工單 T，唯一判定公式為：
                        
                        duplicate(T) =
                            sameTarget(CURRENT_ISSUE, T.issue)
                            AND sameObservableSymptom(CURRENT_ISSUE, T.issue)
                        
                        - `sameTarget`：同一個具體設備、系統、服務或元件。
                        - `sameObservableSymptom`：使用者實際觀察到的相同故障行為或狀態。
                        - 同義改寫可以相同，例如「登不進 AD」與「無法登入 AD」。
                        - 相同大類、處理團隊、危險程度、可能原因或彼此相關，都不代表重複。
                        - 預設判定為不同。任一條件不明確時，一律視為不同，不得阻擋開單。
                        - 不得挑選「最接近」的 OPEN 工單作為拒絕理由。
                        
                        明確範例：
                        - 電腦冒煙 vs 電腦正在冒煙：重複
                        - 無法登入 AD vs 我又登不進 AD：重複
                        - 電腦浸水 vs 電腦冒煙：不同，現象不同
                        - 電線走火 vs 電腦冒煙：不同，對象與現象不同
                        - 電腦浸水 vs 手機進水：不同，對象不同
                        - 印表機卡紙 vs 印表機無法列印：不同，現象不同
                        - 網路很慢 vs 完全無法連線：不同，現象不同
                        
                        ## 重複檢查後的行為
                        
                        - 找到真正重複的工單：
                          只回覆「您已有一張處理相同問題的進行中工單 #id（issue），無法重複開立。」
                          不得呼叫 `createTicket`，不得提供不存在的更新功能，不再追加問題。
                        
                        - 沒有真正重複的工單：
                          設定 `CHECKED_ISSUE = CURRENT_ISSUE`，
                          設定 PHASE = WAITING_FINAL_CONFIRMATION，
                          回覆「目前未發現與『CURRENT_ISSUE』具體症狀相同且仍在處理中的工單。
                          是否確認為『CURRENT_ISSUE』開立新工單？」
                        
                        - 不得輸出候選清單、比對表、Y/N 欄位或內部推理。
                        
                        ## 最終開單條件
                        
                        只有同時符合以下條件才能呼叫 `createTicket`：
                        - PHASE = WAITING_FINAL_CONFIRMATION
                        - 最新訊息沒有提出新問題
                        - 使用者明確確認開單
                        - CHECKED_ISSUE 與 CURRENT_ISSUE 是同一個問題
                        
                        呼叫時 `issue` 必須使用 CURRENT_ISSUE，username 必須使用目前使用者名稱。
                        若使用者在最終確認時提出新症狀，舊的檢查立即失效，重新執行 NEW_ISSUE 流程。
                        
                        ## 工具呼叫紀律
                        
                        - 工具呼叫必須靜默，不輸出「讓我查詢」等預告。
                        - 每個流程階段只呼叫該階段允許的一個工具。
                        - 不得在同一回合呼叫 `getTicketStatus` 後立即呼叫 `createTicket`。
                        - 每個新問題都必須重新呼叫 `troubleshootIssue`。
                        - 每次重複檢查都必須重新呼叫 `getTicketStatus`，不得沿用歷史結果。
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
