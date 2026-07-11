package com.example.myspringai_mcp_client.util;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpProgress;
import org.springframework.stereotype.Component;

/**
 * 接收並記錄 MCP server 在執行 tool 過程中回報的進度通知。
 * <p>
 * 什麼是 progress notification：
 * 當 client 呼叫 MCP tool 時，可以在 toolContext 裡附上一個 progressToken（唯一識別碼）。
 * Server 收到這個 token 後，在 tool 執行過程中可以多次呼叫 ctx.progress(...) 回報進度，
 * 每次都帶著同一個 token，讓 client 知道這個進度屬於哪一次 tool call。
 * <p>
 * 完整流程：
 * <p>
 * Client 發出 tool call，toolContext 帶入 progressToken（UUID）
 * │
 * ▼  MCP server 收到 progressToken，tool 執行中
 * Server 呼叫 ctx.progress(token, progress, message)，送出 ProgressNotification
 * │
 * ▼  Spring AI 收到 notification，找到對應的 @McpProgress handler
 * onProgress(notification) 被呼叫，把進度印到 log
 * <p>
 * progressToken 的作用：
 * 同一時間可能有多個 tool call 並行，progressToken 讓 client 能區分
 * 「這個進度更新是哪一次 tool call 送來的」。
 */
@Slf4j
@Component
public class HelpDeskToolProgressListener {

    // @McpProgress(clients = ...) 指定只接收 helpdesk server 的 progress notification，避免誤攔截其他 server 的通知。
    @McpProgress(clients = "helpdesk-ticket-mcp-server-stdio")
    public void onProgress(McpSchema.ProgressNotification notification) {
        // notification 欄位對應 mcp server 端 ctx.progress() 傳入的參數：
        //   progress()      → 目前完成百分比（例如 50.0 代表 50%）
        //   progressToken() → 與 toolContext 裡傳入的 progressToken 相同，識別是哪次 tool call
        //   message()       → server 自訂的進度說明文字（例如 "正在查詢工單..."）
        log.info("進度更新 - 已完成 {}%，請求 ID：{}，訊息：{}",
                notification.progress(),
                notification.progressToken(),
                notification.message());
    }
}

/*
  Client 端（McpClientController）
  ────────────────────────────────────────────────────────────────
  chatClient.prompt()
      .toolContext(Map.of("progressToken", "abc-123"))  // ← 產生一個 UUID 作為 token
      .call().content()
      │
      │  Spring AI 把這個 token 附在 tool call request 裡，透過 MCP protocol 送給 server
      ▼

  ════════════════════════════════════════════════════════════════
  Server 端（getTicketStatus）
  ════════════════════════════════════════════════════════════════
      │
      │  ctx 物件裡已經持有 client 傳來的 progressToken = "abc-123"
      ▼
  查詢 tickets（快速完成）

  for i = 0..9:
      Thread.sleep(1000)           // 等 1 秒
      ctx.progress(percent, msg)   // 用 "abc-123" 這個 token，透過 MCP protocol
      │                            // 送出一個 ProgressNotification 給 client
      │
      ▼  MCP protocol 把 notification 送回 client

  ════════════════════════════════════════════════════════════════
  Client 端（HelpDeskToolProgressListener）
  ════════════════════════════════════════════════════════════════
      │
      ▼  Spring AI 收到 ProgressNotification，
         找到 @McpProgress(clients = "helpdesk-...") 的 handler

  onProgress(notification) 被呼叫
      notification.progressToken() = "abc-123"   // 對應到當初送出的 token
      notification.progress()      = 10, 20...   // server 每秒回報一次
      notification.message()       = "已完成 10%..."
      │
      ▼
  log.info("進度更新 - 已完成 10%，請求 ID：abc-123，訊息：...")
  log.info("進度更新 - 已完成 20%，請求 ID：abc-123，訊息：...")
  ...（每秒一次，共 10 次）

  ════════════════════════════════════════════════════════════════
  10 秒後，server 的 for 迴圈結束，return tickets
      │
      ▼  LLM 收到 tool 執行結果，回覆使用者
  Client 端的 .call().content() 才真正返回

  關鍵點：onProgress() 和 .call().content() 是在不同 thread 上運作的。call().content() 阻塞等待 tool 完成，同時 onProgress() 每秒被呼叫一次印 log，兩者並行。
* */