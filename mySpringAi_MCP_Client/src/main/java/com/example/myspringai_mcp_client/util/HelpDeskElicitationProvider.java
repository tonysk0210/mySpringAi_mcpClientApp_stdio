package com.example.myspringai_mcp_client.util;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpElicitation;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MCP client 端的 Elicitation（補充資料）請求處理器。
 *
 * <p><b>什麼是 Elicitation？</b><br>
 * 當 MCP server 在執行 tool 的過程中發現缺少必要資訊時，
 * 可以主動向 client 發出 elicitation request，暫停執行並等待 client 補充資料，
 * client 回應後 server 再繼續完成 tool 的執行。
 *
 * <p><b>完整互動流程：</b>
 * <pre>
 * 使用者：「幫我建一張 ticket」 → POST /api/chat
 *     │
 *     ▼  LLM 決定呼叫 createTicket tool
 * MCP server 執行 tool，發現缺少 priority 和 contactPhone
 *     │
 *     ▼  MCP protocol 把 ElicitRequest 送回 client（含提示訊息與欄位 schema）
 * Spring AI 找到 @McpElicitation handler → handleElicitationRequest() 被呼叫
 *     │
 *     ├─ 1. ElicitationSessionStore.register()：建立 session，取得 sessionId
 *     ├─ 2. ElicitationSseService.push()：透過 SSE 推送提示訊息 + schema 給前端
 *     └─ 3. CompletableFuture.get()：阻塞此 thread，等待使用者在聊天框輸入
 *                     │
 *                     │  使用者看到提示後，在聊天框輸入補充資料並送出
 *                     │  POST /api/chat → McpClientController.handleElicitationChatResponse()
 *                     │  用 LLM 解析使用者輸入 → ElicitationSessionStore.complete()
 *                     ▼
 *     CompletableFuture 解除阻塞，取得解析好的資料 Map
 *     │
 *     ▼  回傳 ElicitResult.ACCEPT + 資料給 MCP server
 * Server 收到資料，繼續完成 createTicket，返回結果給 LLM
 *     │
 *     ▼
 * 原本阻塞中的 POST /api/chat 解除，LLM 回覆：「ticket 已成功建立」
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HelpDeskElicitationProvider {

    private final ElicitationSessionStore sessionStore;
    private final ElicitationSseService sseService;

    /**
     * 接收 MCP server 的 elicitation request，透過 SSE 通知前端聊天框顯示提示，
     * 然後阻塞等待使用者在聊天框完成輸入後才回傳結果給 server。
     *
     * <p>{@code @McpElicitation(clients = ...)} 中的 clients 填入 MCP server 的 connection name，
     * 確保只接收指定 server 的 elicitation，避免誤攔截其他 server 的請求。
     *
     * @param request MCP server 發來的請求，固定有：
     *                <ul>
     *                  <li>{@code message()} - 描述為何需要補充資料的說明文字，直接顯示給使用者</li>
     *                  <li>{@code mode()} - 識別實際型別：{@code "form"} 或 {@code "url"}</li>
     *                </ul>
     *                若型別為 {@link McpSchema.ElicitFormRequest}，額外有：
     *                <ul>
     *                  <li>{@code requestedSchema()} - JSON Schema，定義需要填入的欄位名稱、型別、合法值</li>
     *                </ul>
     * @return {@link McpSchema.ElicitResult}，三種 Action：
     * <ul>
     *   <li>{@code ACCEPT} - 使用者成功提交資料，附上解析好的 Map 回傳給 server</li>
     *   <li>{@code CANCEL} - 等待逾時（超過 5 分鐘無回應），server 可選擇終止或降級處理</li>
     *   <li>{@code DECLINE} - 發生例外，通知 server 本次 elicitation 失敗</li>
     * </ul>
     */
    @McpElicitation(clients = "helpdesk-ticket-mcp-server-stdio")
    public McpSchema.ElicitResult handleElicitationRequest(McpSchema.ElicitRequest request) {

        // 步驟 1：在 SessionStore 建立一個新的 session。
        // 內部產生唯一 sessionId 並建立 CompletableFuture，
        // 後續的 hasPending() 檢查就是看這裡有沒有未完成的 session。
        String sessionId = sessionStore.register(request);
        log.info("Elicitation session 已建立，等待使用者在聊天框輸入 sessionId={}", sessionId);

        // 步驟 2：透過 SSE 把提示訊息和欄位 schema 推送給前端聊天框。
        // ElicitRequest 是 interface，requestedSchema() 只存在於 ElicitFormRequest（表單模式）。
        // ElicitUrlRequest（URL 模式）是引導使用者打開網頁填資料，沒有 schema，給空 Map。
        Map<String, Object> schema = (request instanceof McpSchema.ElicitFormRequest formRequest)
                ? formRequest.requestedSchema()
                : Map.of();
        sseService.push(sessionId, request.message(), schema); // 推送的是「問題」：需要填哪些欄位

        // sseService.push 到 sessionStore.getFuture 的簡易流程
        /**
         *   sseService.push(sessionId, message, schema)
         *       │
         *       │  推送的是「問題」：需要填哪些欄位
         *       ▼
         *   前端聊天框顯示：
         *       「需要補充：priority（HIGH/MEDIUM/LOW）和 contactPhone」
         *                             ↑ 這是從 schema 解析出來的
         *
         *   使用者輸入：「HIGH，0912-345-678」
         *       │
         *       ▼  POST /api/chat → LLM 解析 → Map
         *       │
         *       │  complete(sessionId, {"priority":"HIGH","contactPhone":"0912-345-678"})
         *       ▼
         *   sessionStore.getFuture(sessionId).get()
         *       │
         *       │  等到的是「答案」：使用者填入的值
         *       ▼
         *   Map<String, Object> userInput = {"priority":"HIGH", "contactPhone":"0912-345-678"}
         */

        // sseService.push 到 sessionStore.getFuture 這中間發生什麼事：
        /**
         *
         * 這中間是 跨執行緒、跨 HTTP 請求 的等待期，整個流程如下：
         *
         *   handleElicitationRequest() thread（被 MCP framework 呼叫，Spring thread pool）
         *   ────────────────────────────────────────────────────────────────────────────────
         *
         *   sseService.push(sessionId, message, schema)
         *       │
         *       │  透過 SSE 長連線，把 elicitation 事件推送到瀏覽器
         *       │  （push() 本身是非同步的，呼叫完立刻返回，不等瀏覽器）
         *       ▼
         *   sessionStore.getFuture(sessionId).get(5, TimeUnit.MINUTES)
         *       │
         *       │  ← 此 thread 在這裡「掛住」，什麼都不做，只是等
         *       │    CompletableFuture 內部用 LockSupport.park() 讓 thread 休眠
         *       │
         *       ▼  ← 整個 Java thread 停在這，佔著資源等待
         *
         *   ════════════════════════════════════════════════════════════
         *             同一時間，另一條平行時間軸正在進行：
         *   ════════════════════════════════════════════════════════════
         *
         *   瀏覽器（EventSource 長連線）
         *       │
         *       ├─ 收到 SSE "elicitation" 事件
         *       ├─ 解析 payload：{ sessionId, prompt, schema }
         *       └─ 在聊天框顯示：「需要補充：priority（HIGH/MEDIUM/LOW）和 contactPhone」
         *
         *   使用者看到提示，輸入「HIGH，0912-345-678」按 Enter
         *
         *   瀏覽器發出第二次 POST /api/chat（message = "HIGH，0912-345-678"）
         *       │
         *       ▼  Tomcat 分配另一條 thread 處理這個 HTTP 請求
         *
         *   McpClientController.chat()
         *       │
         *       ├─ hasPending() = true → 進入 handleElicitationChatResponse()
         *       ├─ parserClient + LLM → 解析出 {"priority":"HIGH","contactPhone":"0912-345-678"}
         *       └─ sessionStore.complete(sessionId, data)
         *               │
         *               ▼  CompletableFuture.complete(data) 被呼叫
         *
         *   ════════════════════════════════════════════════════════════
         *             回到第一條 thread：
         *   ════════════════════════════════════════════════════════════
         *
         *   sessionStore.getFuture(sessionId).get()
         *       │
         *       │  ← CompletableFuture 被 complete()，LockSupport.unpark() 喚醒此 thread
         *       ▼
         *   Map<String, Object> userInput = {"priority":"HIGH", "contactPhone":"0912-345-678"}
         *       │
         *       ▼  繼續執行，回傳 ElicitResult.ACCEPT + data 給 MCP server
         */

        try {
            // 步驟 3：阻塞此 thread 等待使用者輸入，最多等 5 分鐘。
            // 當使用者在聊天框送出回應後，McpClientController 會呼叫
            // ElicitationSessionStore.complete()，解除此處的阻塞並取得資料。
            Map<String, Object> userInput = sessionStore.getFuture(sessionId) // 等到的是「答案」：使用者填入的值
                    .get(5, TimeUnit.MINUTES);

            log.info("Elicitation 收到使用者資料，回傳 ACCEPT sessionId={} data={}", sessionId, userInput);
            return McpSchema.ElicitResult.builder(McpSchema.ElicitResult.Action.ACCEPT)
                    .content(userInput)
                    .build();

        } catch (TimeoutException e) {
            // 使用者 5 分鐘內未回應，主動取消 session 並告知 server
            log.warn("Elicitation 等待逾時（5 分鐘），回傳 CANCEL sessionId={}", sessionId);
            sessionStore.cancel(sessionId);
            return McpSchema.ElicitResult.builder(McpSchema.ElicitResult.Action.CANCEL).build();

        } catch (Exception e) {
            // 發生非預期例外（e.g. thread 被中斷），告知 server 拒絕本次 elicitation
            log.error("Elicitation 發生例外，回傳 DECLINE sessionId={}", sessionId, e);
            return McpSchema.ElicitResult.builder(McpSchema.ElicitResult.Action.DECLINE).build();
        }
    }
}
