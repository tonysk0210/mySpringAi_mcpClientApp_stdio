================================================================================
 mySpringAi_mcpClientApp_stdio
 Spring AI 2.0 × Model Context Protocol (MCP) over stdio — 全端示範專案
================================================================================

一個 end-to-end 的 MCP 示範 monorepo，展示三件事：
  1. 如何用 Spring AI 寫一個 MCP Server（stdio transport）
  2. 如何用 Spring AI 寫一個 MCP Client，同時串接多個 MCP Servers
     （filesystem / github / 自製 helpdesk）
  3. 如何用 React SPA 消費這個 Client 提供的 REST + SSE API，並實作真實
     的 elicitation（工具執行中暫停，向使用者索取補充資料）流程

程式碼註解、UI 文字、log 訊息一律使用**繁體中文**。

================================================================================
 目錄
================================================================================

  1. 專案結構
  2. 技術棧
  3. 前置需求
  4. 環境變數
  5. 建置與執行（Windows / macOS）
  6. Client 對外提供的 API
  7. 三個 MCP 能力示範
  8. 用 MCP Inspector 單獨測試 Server
  9. 開發時的關鍵陷阱
 10. 資料庫
 11. 相關指引文件

================================================================================
 1. 專案結構
================================================================================

  mySpringAi_mcpClientApp_stdio/
  │
  ├── mySpringAi_MCP_Server_stdio/       ← MCP Server（stdio，Help Desk 工單）
  │   ├── src/main/java/.../tool/
  │   │     └── HelpDeskTicketTool.java  ← 唯一對外的 @McpTool 進入點
  │   ├── src/main/java/.../service/     ← Ticket service + JPA repo
  │   ├── src/main/java/.../payload/     ← Tool 輸入 DTO（含 elicitation schema）
  │   ├── src/main/java/.../config/      ← DataInitializer（seed 已結案工單）
  │   ├── h2db/                          ← H2 檔案 DB（*.mv.db 已 gitignore）
  │   └── target/mySpringAi_MCP_Server_stdio-0.0.1-SNAPSHOT.jar
  │
  ├── mySpringAi_MCP_Client/             ← MCP Client + REST/SSE Backend
  │   ├── src/main/java/.../controller/
  │   │     ├── FileSystemMcpController  ← POST /api/filesystem/chat
  │   │     ├── GithubMcpController      ← POST /api/github/chat
  │   │     └── HelpDeskController       ← POST /api/helpdesk/chat + SSE
  │   ├── src/main/java/.../advisor/     ← Token 稽核 / PrettyLogger 兩層 advisor
  │   ├── src/main/java/.../util/
  │   │     ├── HelpDeskElicitationProvider   ← @McpElicitation handler
  │   │     ├── HelpDeskSamplingProvider      ← @McpSampling handler
  │   │     ├── HelpDeskLogBridge             ← @McpLogging bridge
  │   │     ├── HelpDeskToolProgressListener  ← @McpProgress listener
  │   │     ├── ElicitationSessionStore       ← 兩條 thread 用 Future 協調
  │   │     ├── ElicitationSseService         ← SSE broadcaster
  │   │     ├── McpServerToolFilter           ← 全域 tool filter（bean）
  │   │     └── ToolUtil                      ← per-request 工具挑選
  │   ├── src/main/resources/
  │   │     ├── application.properties
  │   │     ├── application-windows.properties  ← Windows stdio 指令
  │   │     └── application-mac.properties      ← macOS stdio 指令
  │   └── mcp-server-stdio/
  │         └── mySpringAi_MCP_Server_stdio-0.0.1-SNAPSHOT.jar  ← 由 Server 拷貝
  │
  └── mcp-ui/                            ← React SPA（Vite）
      ├── src/pages/
      │     ├── HelpdeskChatPage.jsx     ← SSE + elicitation 最複雜的一頁
      │     ├── FileSystemChatPage.jsx
      │     └── GithubChatPage.jsx
      ├── src/components/
      │     ├── ChatBox.jsx              ← 三頁共用的聊天 UI
      │     └── Navbar.jsx
      ├── src/context/UsernameContext.jsx  ← Username Context（localStorage）
      ├── src/api/client.js              ← axios，baseURL: /api
      └── vite.config.js                 ← /api → http://localhost:8080 proxy

  重要：Server 與 Client **不是** Maven 模組依賴。Client 透過 stdio 啟動
        Server 的方式是「執行 mcp-server-stdio/*.jar」— 這是一份實體 JAR
        拷貝，Server 端修改後必須重打並手動覆蓋。

================================================================================
 2. 技術棧
================================================================================

  Server：
    - Java 25
    - Spring Boot 4.1.0
    - Spring AI 2.0.0 (spring-ai-starter-mcp-server)
    - Spring Data JPA + Hibernate + H2 (file DB, AUTO_SERVER=true)
    - Lombok

  Client：
    - Java 25
    - Spring Boot 4.1.0
    - Spring AI 2.0.0 (spring-ai-starter-mcp-client, spring-ai-starter-model-openai)
    - OpenAI gpt-4o-mini（可於 application.properties 更改）
    - Web MVC + SSE（同步）
    - Lombok

  Frontend：
    - React 19.2
    - Vite 8
    - React Router 7
    - axios
    - Plain CSS（無 Tailwind / CSS Modules）
    - ESLint flat config（react-hooks + react-refresh plugin）

  Spring AI 2.0.0 的 MCP annotation 表面（@McpElicitation、@McpSampling、
  @McpLogging、@McpProgress）在不同 milestone 之間有變動；升級版本時務必
  同時檢查兩邊。

================================================================================
 3. 前置需求
================================================================================

  必要：
    - JDK 25（Server 與 Client 皆為 Java 25，較舊的 JDK 無法編譯）
    - Maven 3.9+（或使用專案內附的 mvnw / mvnw.cmd）
    - Node.js 20+ 與 npm（前端 + filesystem MCP server 都用到 npx）
    - Docker Desktop（用於執行 GitHub MCP server container）
    - OpenAI API Key
    - GitHub Personal Access Token（若要用 /api/github/chat）

  可選：
    - MCP Inspector（`npx @modelcontextprotocol/inspector`）— 單獨測試 Server
    - DataGrip / H2 Console — 查看 Server 的 H2 資料庫

================================================================================
 4. 環境變數
================================================================================

  OPENAI_API_KEY                     必要。Client 呼叫 OpenAI 使用。
  GITHUB_PERSONAL_ACCESS_TOKEN       必要（若要用 GitHub 頁）。Docker 啟動
                                     github MCP server 時透過 -e 帶入。
  MCP_FILESYSTEM_ROOT                可選。filesystem MCP server 允許存取
                                     的根目錄。未設定時 fallback：
                                       Windows: %USERPROFILE%\Desktop\mymcp
                                       macOS:   ~/Desktop/mymcp

  PowerShell（Windows）設定方式：
    $env:OPENAI_API_KEY = "sk-..."
    $env:GITHUB_PERSONAL_ACCESS_TOKEN = "ghp_..."
    $env:MCP_FILESYSTEM_ROOT = "C:\path\to\mcp\root"

  bash / zsh（macOS）：
    export OPENAI_API_KEY="sk-..."
    export GITHUB_PERSONAL_ACCESS_TOKEN="ghp_..."
    export MCP_FILESYSTEM_ROOT="$HOME/Desktop/mymcp"

  提示：所有密鑰皆以環境變數傳入，切勿寫死於 properties 或 commit 進版本庫。

================================================================================
 5. 建置與執行（Windows / macOS）
================================================================================

  ── 5.1 打包並更新 Server JAR（動到 Server 時必做）─────────────────

    Windows PowerShell：
      cd mySpringAi_MCP_Server_stdio
      .\mvnw.cmd clean package -DskipTests
      Copy-Item target\mySpringAi_MCP_Server_stdio-0.0.1-SNAPSHOT.jar `
                ..\mySpringAi_MCP_Client\mcp-server-stdio\ -Force

    macOS：
      cd mySpringAi_MCP_Server_stdio
      ./mvnw clean package -DskipTests
      cp target/mySpringAi_MCP_Server_stdio-0.0.1-SNAPSHOT.jar \
         ../mySpringAi_MCP_Client/mcp-server-stdio/

    Repo 已內附一份預打包 JAR，若沒動 Server 可跳過此步驟。

  ── 5.2 啟動 Client（會自動以 stdio 子行程拉起 Server）─────────────

    Windows PowerShell：
      cd mySpringAi_MCP_Client
      .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=windows"

    macOS：
      cd mySpringAi_MCP_Client
      ./mvnw spring-boot:run -Dspring-boot.run.profiles=mac

    Client 監聽 :8080。啟動 log 中會看到三個 MCP servers 逐一連上，並列出
    每個 controller 開放到的 tool 清單（由 ToolUtil.selectToolsFor 印出）。

    Profile 也會由 main() 自動根據 os.name 選擇，但顯式指定較可靠。

  ── 5.3 啟動前端 ───────────────────────────────────────────────────

    cd mcp-ui
    npm install                # 第一次或依賴變動時
    npm run dev

    Vite 監聽 :5173，並將 /api/** proxy 到 http://localhost:8080。
    瀏覽器打開 http://localhost:5173 即可使用。首次會要求輸入 username。

  ── 5.4 測試 ───────────────────────────────────────────────────────

    Server（全部）：
      cd mySpringAi_MCP_Server_stdio && .\mvnw.cmd clean test

    Client（全部）：
      cd mySpringAi_MCP_Client && .\mvnw.cmd clean test

    Client（單一測試類別）：
      .\mvnw.cmd -Dtest=ElicitationSessionStoreTest test

    Frontend：
      cd mcp-ui && npm run lint     （目前無單元測試設定）

  ── 5.5 Production 打包 ────────────────────────────────────────────

    Client JAR：
      cd mySpringAi_MCP_Client && .\mvnw.cmd package
      → target/mySpringAi_MCP_Client-0.0.1-SNAPSHOT.jar

    Frontend 靜態檔：
      cd mcp-ui && npm run build
      → dist/

================================================================================
 6. Client 對外提供的 API
================================================================================

  所有請求皆需帶 header：
      username: <任意識別字串>
  該 header 用來隔離 chat memory 與 SSE session。

  ── Helpdesk ─────────────────────────────────────────────────────────
  POST /api/helpdesk/chat
      Body: { "message": "...", "sessionId": "..."（elicitation 回覆時才帶）}
      Header: username

  GET  /api/helpdesk/elicitation/stream?username=<name>
      SSE。長效連線，後端會 push 名為 "elicitation" 的事件，含 sessionId、
      prompt、requestedSchema。前端顯示問卷讓使用者回覆。

  POST /api/helpdesk/elicitation/{sessionId}/cancel
      Header: username
      使用者取消 elicitation。後端會 unpark 卡住的 tool call thread，
      回傳 CANCEL 給 Server。

  ── Filesystem ───────────────────────────────────────────────────────
  POST /api/filesystem/chat
      Body: { "message": "..." }
      Header: username
      LLM 可讀寫 MCP_FILESYSTEM_ROOT 底下的檔案。

  ── GitHub ───────────────────────────────────────────────────────────
  POST /api/github/chat
      Body: { "message": "..." }
      Header: username
      LLM 可透過 GitHub PAT 操作 GitHub repos。

  三個 controller 各自持有 **獨立** 的 MessageWindowChatMemory，即使
  username 相同，對話歷史也不會互相滲入。

================================================================================
 7. 三個 MCP 能力示範
================================================================================

  Server 側的 HelpDeskTicketTool.java 各以一個 @McpTool 對應一項 MCP 能力：

  (1) createTicket — Elicitation ────────────────────────────────────
      工具呼叫執行到一半，Server 用 ctx.elicit(...) 暫停，透過 MCP 通道
      向 Client 索取「補充資訊」（priority、contactPhone）。

      Client 側的協調流程：
        1. LLM 決定呼叫 createTicket
        2. @McpElicitation handler（HelpDeskElicitationProvider）收到請求，
           將 prompt + schema 寫入該 username 的 SSE stream，然後 **阻塞於
           CompletableFuture.get(5min)**，Tomcat thread 被 park
        3. 瀏覽器 EventSource 收到事件 → ChatBox 進入 isEliciting 狀態
        4. 使用者在聊天框回覆 → 前端 POST /api/helpdesk/chat 並帶上
           sessionId → Controller 路由到 handleElicitationChatResponse
        5. parserClient（**無工具的獨立 ChatClient**）把自然語言轉成 Map →
           sessionStore.complete(sessionId, owner, data) 完成 Future
        6. 被 park 的 thread 醒來，回傳 ElicitResult.ACCEPT + data
        7. Server 用補足的資料完成 tool call，第一次 POST 才真正回傳

      不變條件：
        - owner (username) 必須在 ElicitRequest.meta() 中
        - parserClient 必須無工具（不然解析步驟本身會觸發巢狀 tool call）
        - 兩端的 request-timeout 必須都設 300s（5 min），與 future.get 對齊
        - SSE 重連時會補送 pendingForOwner，中途重新整理不會遺失提示

  (2) getTicketStatus — Progress notifications ──────────────────────
      Server 迴圈中呼叫 ctx.progress(...) 發送進度百分比。Client 側由
      HelpDeskToolProgressListener 接收；只有 HelpDeskController 會在
      toolContext 中放入 progressToken(UUID)，所以只有它會收到進度。

  (3) troubleshootIssue — Sampling ──────────────────────────────────
      Server 呼叫 ctx.sample(...) 借用 **Client 端的 LLM**。Server 準備
      好 system prompt + 歷史已解決工單當知識庫，讓 Client LLM 生成
      自助排障建議。Client 端 HelpDeskSamplingProvider 用的是原生
      ChatModel（不是 ChatClient），避免帶入 MCP tools 造成無限迴圈。

  額外的可觀測性：
      HelpDeskLogBridge (@McpLogging) — Server 的 ctx.info(...) 轉成
      Client 的 SLF4J log；clients 屬性用的是 connection key，不是
      Server 自報的 application name。

================================================================================
 8. 用 MCP Inspector 單獨測試 Server
================================================================================

  想在不啟動整個 Client 的情況下手動測 Server 的 tools：

      cd mySpringAi_MCP_Server_stdio
      .\mvnw.cmd clean package -DskipTests
      npx @modelcontextprotocol/inspector

  然後在 Inspector UI：
      Transport:  stdio
      Command:    java
      Arguments:  -jar D:\...\target\mySpringAi_MCP_Server_stdio-0.0.1-SNAPSHOT.jar

  連上之後可以直接看 tools/resources/prompts 清單、手動呼叫 tool、
  觀察 schema 與回傳值。

================================================================================
 9. 開發時的關鍵陷阱
================================================================================

  ● Server 端 stdout 污染會弄壞 MCP。
    Helpdesk Server 是 Spring Boot，預設 logback 會把啟動 log 印到 stdout，
    這會污染 stdio 上的 JSON-RPC 傳輸。Server 的 logback 已改導向
    System.err — 動到 Server 時務必驗證這點仍然成立。application.properties
    也刻意設 `spring.main.web-application-type=none` 與 `logging.level.root=
    error` 與 `spring.main.banner-mode=off`。

  ● Windows npx 必須用 cmd /c 包起來，docker / java 則不需要。
    這也是 application-windows.properties 與 application-mac.properties 要
    分開的主因。加新的 MCP server 前先確認平台差異。

  ● Client main() 兩段式啟動不可重構掉。
    先檢查 os.name，在 run() 之前呼叫 setAdditionalProfiles(...)。若改成
    單純的 SpringApplication.run(...)，profile 尚未載入時 MCP 連線設定會
    是空的，會靜默 fallback 並無法運作。

  ● Tool 選擇有兩層 — 別混淆。
    - McpServerToolFilter（bean、全域、快取）由 application.properties 的
      `mcp.tool-filter.blocked-*` 設定，用於全域封鎖。
    - ToolUtil.selectToolsFor(...) 是 per-request 手動挑選，controller
      用它把「自己該看到哪些 tools」限縮出來。

  ● parserClient 必須是獨立的 ChatClient。
    Spring AI 的 ChatClient.Builder 是 prototype scope，注入兩個 builder
    參數會得到兩個獨立實例。elicitation 的自然語言 → Map 解析必須用**無
    工具的** parserClient，否則解析步驟本身可能觸發巢狀 tool call。

  ● SSE 重連。
    ElicitationSseService 在使用者訂閱時會 replay pendingForOwner(username)，
    讓 elicitation 進行中頁面重新整理不會遺失提示。前端也有 2 秒重試機制。
    seenElicitationSessionsRef 用於過濾重連後的重複 prompt。

  ● Advisors chain 順序：
    TokenUsageAuditAdvisor(order=-1) → PrettyLoggerAdvisor(order=0) →
    MessageChatMemoryAdvisor。每個 request 開始時呼叫
    prettyLoggerAdvisor.reset()，讓 #N 呼叫計數從 1 開始。

  ● Spring AI 版本鎖定：
    Server 與 Client 兩份 pom 都鎖 spring-ai.version = 2.0.0，透過
    spring-ai-bom 匯入。升級前檢查 @McpElicitation / @McpSampling /
    @McpLogging / @McpProgress 的 API 是否變動。

================================================================================
 10. 資料庫
================================================================================

  H2 檔案 DB 位於 mySpringAi_MCP_Server_stdio/h2db/mcpserver_stdio，
  啟用 AUTO_SERVER=true，可以在 App 執行中同時用 H2 Console 或 DataGrip
  連線。ddl-auto=update，適合本地開發，正式環境請改用 migration 流程。

  首次啟動時 DataInitializer 會 seed 約 9 筆 status=CLOSED 的工單，
  當作 troubleshootIssue（sampling）的知識庫。

  h2db/ 目錄本身在 repo 內；只有 *.mv.db、*.lock.db、*.trace.db 被
  .gitignore 忽略。切勿把 DB dump 檔提交進來。

================================================================================
 11. 相關指引文件
================================================================================

  各層更深入的說明位於子專案內：

    CLAUDE.md（本 repo 根與各子專案皆有一份，Claude Code 專用）
      D:\...\CLAUDE.md                           ← 跨專案總覽
      D:\...\mySpringAi_MCP_Server_stdio\CLAUDE.md
      D:\...\mySpringAi_MCP_Client\CLAUDE.md
      D:\...\mcp-ui\CLAUDE.md

    AGENTS.md（Codex / 其他 agent 專用，含 commit / PR / 測試規範）
      D:\...\mySpringAi_MCP_Server_stdio\AGENTS.md
      D:\...\mySpringAi_MCP_Client\AGENTS.md
      D:\...\mcp-ui\AGENTS.md

  第一次要送 PR 前建議先讀過對應那份 AGENTS.md 中的 commit / 測試 / 格式
  規範。

================================================================================
