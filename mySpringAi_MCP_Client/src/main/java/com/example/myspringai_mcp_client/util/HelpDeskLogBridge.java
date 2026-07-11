package com.example.myspringai_mcp_client.util;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpLogging;
import org.springframework.stereotype.Component;

/**
 * 將 MCP server 端透過 MCP protocol 發出的 log notification，
 * 橋接（bridge）到 MCP client 端（本應用程式）的 SLF4J logger。
 * <p>
 * 完整的訊息傳遞路徑如下：
 * <p>
 * MCP Server 端呼叫 ctx.info("message")
 * │
 * ▼  轉換成 MCP protocol 的 logging/message notification
 * MCP Client 收到 notification
 * │
 * ▼  Spring AI 掃描所有標注 @McpLogging 的方法，找到對應的 client name
 * HelpDeskLogBridge.onServerLog(level, source, message) 被呼叫
 * │
 * ▼  透過 SLF4J 輸出到本機 log（console / file）
 * <p>
 * 這個機制讓你不需要去看 MCP server 自己的 log 檔，
 * server 端的重要訊息會直接整合到 client 端的 log 流中一起觀察。
 */
@Slf4j
@Component
public class HelpDeskLogBridge {

    /**
     * 接收來自指定 MCP server 的 log notification 並寫入本機 log。
     *
     * @McpLogging clients 填入 MCP server 的 connection name（須與設定檔一致）：
     * - stdio server：application-{profile}.properties 中
     * spring.ai.mcp.client.stdio.connections.<name> 的 <name>
     * 例如：helpdesk-ticket-mcp-server-stdio
     * - HTTP server： spring.ai.mcp.client.streamable-http.connections.<name> 的 <name>
     * 例如：myremotemcp
     * <p>
     * MCP logging notification 的欄位對應關係：
     * level  （MCP: level）  → INFO / DEBUG / WARNING / ERROR
     * source （MCP: logger） → server 端 logger 的名稱或來源識別
     * message（MCP: data）   → server 端實際要傳達的訊息內容
     */
    @McpLogging(clients = "helpdesk-ticket-mcp-server-stdio")
    public void onServerLog(McpSchema.LoggingLevel level, String source, String message) {
        log.info("收到伺服器日誌 - 等級: {}, 來源: {}, 訊息: {}", level, source, message);
    }
}

/*
  Server 端（MCP Server）                    Client 端（HelpDeskLogBridge）
  ─────────────────────────────────────────────────────────────────────
  ctx.log(spec -> spec
      .level(McpSchema.LoggingLevel.INFO)  →  McpSchema.LoggingLevel level
      .logger(MCP_LOGGER)                  →  String source
      .message(message));                  →  String message

  MCP protocol 在傳輸時把這三個欄位封裝成 notification：

  {
    "method": "notifications/message",
    "params": {
      "level":  "info",
      "logger": "<MCP_LOGGER 的值>",
      "data":   "<message 的值>"
    }
  }
* */