package com.example.myspringai_mcp_client.controller;

import com.example.myspringai_mcp_client.advisor.PrettyLoggerAdvisor;
import com.example.myspringai_mcp_client.advisor.TokenUsageAuditAdvisor;
import com.example.myspringai_mcp_client.payload.ChatPayload;
import com.example.myspringai_mcp_client.util.ToolUtil;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * FileSystem MCP 聊天端點，只提供 filesystem MCP tools。
 */
@Slf4j
@RestController
@RequestMapping("/api/filesystem")
public class FileSystemMcpController {

    private final ChatClient chatClient;
    private final PrettyLoggerAdvisor prettyLoggerAdvisor = new PrettyLoggerAdvisor();

    // FileSystem 專屬記憶體。
    private final ChatMemory chatMemory = MessageWindowChatMemory.builder().build();

    // filesystem tools，啟動時查詢一次並快取。
    private final ToolCallback[] tools;

    @Autowired
    public FileSystemMcpController(ChatClient.Builder chatClientBuilder,
                                   List<McpSyncClient> mcpClients) {
        this.tools = ToolUtil.selectToolsFor(mcpClients, "filesystem", null);

        this.chatClient = chatClientBuilder
                .defaultSystem("""
                        回答時請使用清楚、易理解且專業的繁體中文。
                        你是 FileSystem MCP 工具助理，只能協助使用者操作目前 filesystem MCP server 授權的根目錄。
                        這個根目錄可依執行環境設定，預設通常是使用者桌面上的 mymcp 資料夾；若系統設定了 MCP_FILESYSTEM_ROOT，則以該設定的目錄為準。
                        mymcp 是授權根目錄本身的名稱，不是根目錄底下的子資料夾；當使用者要求列出 mymcp 或桌面 mymcp 內容時，請使用 "." 代表目前授權根目錄。
                        你只能使用 filesystem MCP 工具處理這個授權根目錄內的檔案與目錄，例如列出目錄、讀取檔案、搜尋檔案或寫入檔案。
                        不得宣稱可以存取整台電腦、任意絕對路徑，或授權根目錄以外的檔案。
                        不支援 hard delete 或永久刪除檔案、目錄、repository、branch、commit history 等不可復原操作。
                        基於測試安全考量，目前只應處理使用者桌面 mymcp 測試資料夾內的檔案與目錄；不要操作其他不相關的本機路徑或檔案範圍。
                        """)
                .defaultAdvisors(
                        new TokenUsageAuditAdvisor(),
                        this.prettyLoggerAdvisor,
                        MessageChatMemoryAdvisor.builder(this.chatMemory).build())
                .build();
    }

    /**
     * FileSystem 聊天端點，可使用 filesystem MCP tools，並具備獨立對話記憶。
     */
    @PostMapping("/chat")
    public String chat(@RequestBody ChatPayload chatPayload,
                       @RequestHeader(value = "username", required = false) String username) {
        prettyLoggerAdvisor.reset();

        return chatClient.prompt()
                .user(chatPayload.message())
                .tools(tools)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, username))
                .call().content();
    }
}
