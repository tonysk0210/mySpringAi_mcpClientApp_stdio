package com.example.myspringai_mcp_client.util;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 為單次 ChatClient request 手動挑選 MCP tools 的 helper。
 *
 * <p><b>為什麼需要這個 class？</b><br>
 * Spring AI 預設會把所有 MCP server 的所有 tools 一次性交給 LLM（透過 defaultTools）。
 * 但在某些情境下，你會希望某次 request「只能用特定 server 的特定 tool」，
 * 例如：summarizeTickets 端點只應使用 helpdesk server 的工具，而不該讓 LLM 誤用 filesystem 工具。
 * 這時就呼叫 {@link #selectToolsFor} 手動篩選，把結果傳給 {@code .tools(toolCallbacks)} 使用。
 *
 * <p><b>與全域 filter 的差異：</b><br>
 * {@code McpServerToolFilter} 是全域封鎖，影響所有 request。<br>
 * 這個 helper 是 per-request 精準選取，不影響其他 request 的工具清單。
 */
public class ToolUtil {

    /**
     * 從所有已連線的 MCP server 中，篩選出符合條件的 tools，
     * 包裝成 {@code ToolCallback[]} 供單次 {@code ChatClient.prompt().tools(...)} 使用。
     *
     * <p><b>篩選邏輯（serverName 和 toolName 皆為「模糊比對 hint」）：</b>
     * <ul>
     *   <li>傳入 {@code null} 或空白 → 視為「全部符合」，不限制該條件</li>
     *   <li>傳入字串 → 用 contains 做不分大小寫的部分比對</li>
     * </ul>
     * 例如：{@code serverName = "helpdesk"} 可以比對到 {@code "helpdesk-ticket-mcp-server-stdio"}。
     *
     * @param mcpClients Spring AI 為每個 MCP server 建立的連線物件清單（由 Spring 自動注入）
     * @param serverName 要篩選的 MCP server 名稱 hint（null 代表不限 server）
     * @param toolName   要篩選的 tool 名稱 hint（null 代表該 server 下的所有 tools）
     * @return 符合條件的 tools 包裝成的 {@code ToolCallback[]}，可直接傳給 {@code .tools(...)}
     */
    public static ToolCallback[] selectToolsFor(List<McpSyncClient> mcpClients, String serverName, String toolName) {

        return mcpClients.stream()
                // 步驟 1：對每個 MCP server client，取出它所有的 tool，攤平成單一 tool stream。
                //         每個 client 對應一個 MCP server（例如 filesystem、github、helpdesk）。
                .flatMap(client -> client.listTools().tools().stream()
                        // 步驟 2：雙重 hint 篩選。
                        //   - client.getServerInfo().name()：server 在 MCP 初始化回應中自報的名稱，
                        //     來自 server 端的 spring.application.name，不一定等於 client 端 application.properties 的 connection key。
                        //   - tool.name()：MCP server 對外宣告的 tool 名稱（例如 "list_directory"）。
                        .filter(tool -> matches(client.getServerInfo().name(), serverName)
                                && matches(tool.name(), toolName))
                        // 步驟 3：把 MCP 原生 tool 包裝成 Spring AI 的 ToolCallback。
                        //         SyncMcpToolCallback 是 Spring AI 提供的橋接器，
                        //         讓 LLM 可以透過 Spring AI 的 tool call 機制去呼叫 MCP tool。
                        .map(tool -> (ToolCallback) SyncMcpToolCallback.builder()
                                .mcpClient(client)
                                .tool(tool)
                                .build()))
                // 步驟 4：收集成陣列，供 .tools(toolCallbacks) 使用。
                .toArray(ToolCallback[]::new);
    }

    // //////////////////////////////////////////
    // 模糊比對 helper
    // //////////////////////////////////////////
    /**
     * 模糊比對 helper：hint 為 null 或空白時視為「不限制」，否則做不分大小寫的包含比對。
     *
     * @param actual 實際值（server 名稱或 tool 名稱）
     * @param hint   使用者傳入的篩選條件，null 或空白代表全部符合
     */
    private static boolean matches(String actual, String hint) {
        return hint == null || hint.isBlank()
                || actual.toLowerCase().contains(hint.toLowerCase());
    }
}
