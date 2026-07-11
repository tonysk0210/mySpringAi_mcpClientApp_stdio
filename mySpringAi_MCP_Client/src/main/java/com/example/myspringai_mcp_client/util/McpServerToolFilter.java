package com.example.myspringai_mcp_client.util;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.McpConnectionInfo;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 全域 MCP tool 過濾器：決定哪些 MCP tools 可以暴露給 LLM，哪些要封鎖。
 * 執行時機：第一次 LLM request 時才執行（lazy），結果快取在 {@code SyncMcpToolCallbackProvider}。
 * 啟動時只建立 bean，不會呼叫 test()；之後每次 LLM call 直接使用快取，不重新過濾。
 * 僅當 MCP server tool 清單在 runtime 變動時（{@code McpToolsChangedEvent}）才重新執行。
 * <p>
 * 兩層封鎖（設定於 application.properties）：
 * server 層：server 名稱包含封鎖片段 → 該 server 所有 tools 全封鎖
 * tool 層：tool 名稱以封鎖前綴開頭 → 僅該 tool 被封鎖
 * <p>
 * 與 ToolUtil.selectToolsFor() 的差異：
 * 此 filter 是全域生效，影響所有 request 的 tool 清單。
 * {@code ToolUtil.selectToolsFor()} 是 per-request 手動精選，只影響單次呼叫。
 */
@Slf4j
@Component
public class McpServerToolFilter implements McpToolFilter {

    // 封鎖整個 server 的片段清單（contains 比對，出現在名稱任何位置即命中）。
    @Value("${mcp.tool-filter.blocked-servers:}")
    private List<String> blockedServers;

    // 封鎖特定 tool 的前綴清單（startsWith 比對，只看 tool 名稱開頭）。
    @Value("${mcp.tool-filter.blocked-tool-prefixes:}")
    private List<String> blockedToolPrefixes;

    /**
     * 每個 MCP tool 都會經過這個方法決定是否允許 LLM 使用。
     * <p>注意：{@code McpServerToolFilter} 本身是 Spring bean（由 Spring 管理生命週期），
     *
     * @param mcpConnectionInfo 提供這個 tool 所屬的 MCP server 連線資訊（包含 server 名稱）
     * @param tool              MCP server 對外宣告的單一 tool（包含 tool 名稱與 schema）
     * @return {@code true} 允許此 tool 暴露給 LLM；{@code false} 靜默排除，LLM 看不到
     */
    @Override
    public boolean test(McpConnectionInfo mcpConnectionInfo, McpSchema.Tool tool) {

        // 1. 取得 MCP server 在協定層的名稱，與 application.properties 的 connection name 相同。
        String serverName = mcpConnectionInfo.initializeResult()
                .serverInfo()
                .name();

        // 2. 取得 MCP tool 的名稱。
        String toolName = tool.name();

        log.info("驗證來自 MCP server: '{}' 的 tool: '{}'", serverName, toolName);

        // 第一層：封鎖整個 server（server 名稱含封鎖片段，該 server 下所有 tools 一律拒絕）
        Optional<String> blockedServer = findMatchedBlockedServer(serverName);
        if (blockedServer.isPresent()) {
            log.warn("工具 '{}' 已被拒絕，因為 MCP 伺服器 '{}' 符合封鎖設定 '{}'",
                    toolName, serverName, blockedServer.get());
            return false;
        }

        // 第二層：封鎖單一 tool（tool 名稱以封鎖前綴開頭，其餘同 server 的 tools 不受影響）
        Optional<String> blockedToolPrefix = findMatchedBlockedToolPrefix(toolName);
        if (blockedToolPrefix.isPresent()) {
            log.warn("工具 '{}' 已被拒絕，因為它符合封鎖工具前綴 '{}'",
                    toolName, blockedToolPrefix.get());
            return false;
        }

        log.info("工具 '{}' 已通過 MCP 伺服器 '{}' 的檢查", toolName, serverName);
        return true;
    }

    // //////////////////////////////////////////
    // 模糊比對 helper
    // //////////////////////////////////////////

    /**
     * server 名稱是否包含任一封鎖片段（contains，不分大小寫）。
     * 回傳命中的片段供 log 使用，未命中回傳 empty。
     */
    private Optional<String> findMatchedBlockedServer(String serverName) {
        String normalized = serverName.toLowerCase(Locale.ROOT);
        return blockedServers.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                // contains 比對：封鎖關鍵字只要是 server 名稱的一部分即視為命中
                // 例如："github" 可命中 "github-mcp-server"
                .filter(s -> normalized.contains(s.toLowerCase(Locale.ROOT)))
                .findFirst();
    }

    /**
     * tool 名稱是否以任一封鎖前綴開頭（startsWith，不分大小寫）。
     * 回傳命中的前綴供 log 使用，未命中回傳 empty。
     */
    private Optional<String> findMatchedBlockedToolPrefix(String toolName) {
        String normalized = toolName.toLowerCase(Locale.ROOT);
        return blockedToolPrefixes.stream()
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                // startsWith 比對：tool 名稱必須以封鎖前綴「開頭」才命中
                // 例如：前綴 "delete_" 可命中 "delete_file"，但不會命中 "list_delete"
                .filter(p -> normalized.startsWith(p.toLowerCase(Locale.ROOT)))
                .findFirst();
    }
}
