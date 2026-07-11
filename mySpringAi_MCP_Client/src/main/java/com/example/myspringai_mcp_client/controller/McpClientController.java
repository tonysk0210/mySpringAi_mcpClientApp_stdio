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
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 通用 MCP 聊天端點，提供 filesystem 與 GitHub MCP 工具。
 * Helpdesk 相關功能由 {@link McpHelpdeskController} 負責。
 */
@Slf4j
@RestController
@RequestMapping("/api")
public class McpClientController {

    private final ChatClient chatClient;
    private final PrettyLoggerAdvisor prettyLoggerAdvisor = new PrettyLoggerAdvisor();

    // 此 controller 專屬的記憶體，與 McpHelpdeskController 的 chatMemory 完全隔離。
    // 即使前端傳入相同的 conversationId，兩個 controller 查的是不同的 store，不會互相汙染。
    private final ChatMemory chatMemory = MessageWindowChatMemory.builder().build();

    // filesystem + github tools，啟動時查詢一次並快取。
    private final ToolCallback[] tools;

    @Autowired
    public McpClientController(ChatClient.Builder chatClientBuilder,
                               List<McpSyncClient> mcpClients) {

        // "filesystem" 可比對到 "secure-filesystem-server"；"github" 可比對到 "github-mcp-server"
        ToolCallback[] filesystemTools = ToolUtil.selectToolsFor(mcpClients, "filesystem", null);
        ToolCallback[] githubTools = ToolUtil.selectToolsFor(mcpClients, "github", null);
        this.tools = Stream.concat(Arrays.stream(filesystemTools), Arrays.stream(githubTools))
                .toArray(ToolCallback[]::new);

        this.chatClient = chatClientBuilder
                .defaultSystem("""
                        回答時請使用清楚、易理解且專業的繁體中文。
                        當使用者詢問 GitHub 相關操作時，必須使用 GitHub MCP 工具（如 get_file_contents、list_files、search_repositories 等），絕對不可使用 filesystem 工具。
                        當使用者詢問本機檔案操作時，才使用 filesystem 工具。
                        """)
                .defaultAdvisors(
                        new TokenUsageAuditAdvisor(),
                        this.prettyLoggerAdvisor,
                        // MessageChatMemoryAdvisor 負責把對話歷史注入每次 LLM 請求，
                        // 並在收到回覆後把新的訊息存回 chatMemory。
                        // conversationId 為必要欄位，每次 request 透過 advisors param 傳入。
                        MessageChatMemoryAdvisor.builder(this.chatMemory).build())
                .build();
    }

    /**
     * 通用聊天端點，可使用 filesystem 和 GitHub MCP 工具，並具備對話記憶。
     * Helpdesk 操作請改用 POST /api/helpdesk/chat。
     */
    @PostMapping("/chat")
    public String chat(@RequestBody ChatPayload chatPayload,
                       @RequestHeader(value = "username", required = false) String username) {
        prettyLoggerAdvisor.reset();

        return chatClient.prompt()
                .user(chatPayload.message() + ". 我的 username 是 " + username)
                .tools(tools)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, username))
                .toolContext(Map.of("progressToken", UUID.randomUUID().toString()))
                .call().content();
    }
}
