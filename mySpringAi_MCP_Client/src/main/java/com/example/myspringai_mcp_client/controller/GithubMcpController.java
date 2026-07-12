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
import java.util.Map;
import java.util.UUID;

/**
 * GitHub MCP 聊天端點，只提供 GitHub MCP tools。
 */
@Slf4j
@RestController
@RequestMapping("/api/github")
public class GithubMcpController {

    private final ChatClient chatClient;
    private final PrettyLoggerAdvisor prettyLoggerAdvisor = new PrettyLoggerAdvisor();

    // GitHub 專屬記憶體。
    private final ChatMemory chatMemory = MessageWindowChatMemory.builder().build();

    // github tools，啟動時查詢一次並快取。
    private final ToolCallback[] tools;

    @Autowired
    public GithubMcpController(ChatClient.Builder chatClientBuilder,
                               List<McpSyncClient> mcpClients) {
        this.tools = ToolUtil.selectToolsFor(mcpClients, "github", null);

        this.chatClient = chatClientBuilder
                .defaultSystem("""
                        回答時請使用清楚、易理解且專業的繁體中文。
                        你是 GitHub MCP 工具助理，只能協助使用者操作 GitHub 遠端 repository。
                        目前允許存取的 GitHub repository 只有 https://github.com/tonysk0210/mymcp；不要操作其他 repository。
                        你只能使用 GitHub MCP 工具處理 repository、branch、file、issue、pull request、commit 等遠端 GitHub API 操作。
                        不得宣稱可以執行本機 git clone、git status、git diff、git add、git commit 或 git push。
                        如果使用者要求 push 本機資料夾或桌面 repository 的修改，必須說明目前只能操作 GitHub 遠端 API，不能代表本機 git CLI 執行 commit/push。
                        若要使用 push_files 或建立/更新遠端檔案，必須已經知道明確的檔案 path 與真實 content；不得使用 placeholder 內容，例如「<您的檔案內容>」。
                        """)
                .defaultAdvisors(
                        new TokenUsageAuditAdvisor(),
                        this.prettyLoggerAdvisor,
                        MessageChatMemoryAdvisor.builder(this.chatMemory).build())
                .build();
    }

    /**
     * GitHub 聊天端點，可使用 GitHub MCP tools，並具備獨立對話記憶。
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
