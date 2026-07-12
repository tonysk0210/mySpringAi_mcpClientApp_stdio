# CLAUDE.md

本檔案為 Claude Code（claude.ai/code）在此 repository 中工作時的指引。

## 專案概述

基於 Java 25 的 Spring Boot 4.1 / Spring AI 2.0 MCP **client**。透過 stdio 連接三個 MCP servers：`filesystem`（npx）、`github`（docker），以及 `helpdesk-ticket-mcp-server-stdio`（位於 `mcp-server-stdio/mySpringAi_MCP_Server_stdio-0.0.1-SNAPSHOT.jar` 的本地 Spring Boot JAR）。對外暴露三個 REST controllers（filesystem / github / helpdesk），內部包裝 `ChatClient` 並使用 OpenAI（`gpt-4o-mini`）。

對應的 server repo 位於 `../mySpringAi_MCP_Server_stdio/`。

## 指令

Windows（PowerShell — 本專案主要平台）：

```powershell
# 建置 + 執行完整測試套件
.\mvnw.cmd clean test

# 執行單一測試類別
.\mvnw.cmd -Dtest=ElicitationSessionStoreTest test

# 打包（產生 target/mySpringAi_MCP_Client-0.0.1-SNAPSHOT.jar）
.\mvnw.cmd package

# 本機執行 — profile 會在 main() 方法中根據 os.name 自動選擇，
# 但你也可以明確指定：
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=windows"
```

macOS：使用 `./mvnw` 並將 profile 設為 `mac`。

必要環境變數：`OPENAI_API_KEY`、`GITHUB_PERSONAL_ACCESS_TOKEN`（透過 docker `-e` 傳入），可選 `MCP_FILESYSTEM_ROOT`（fallback：`%USERPROFILE%\Desktop\mymcp` / `~/Desktop/mymcp`）。

## 架構

### Profile 啟動流程（`MySpringAiMcpClientApplication`）
`main()` 刻意採用兩段式：先檢查 `os.name`，在 `run()` **之前** 呼叫 `setAdditionalProfiles("windows"|"mac")`。這麼做的目的是從 `application-windows.properties` / `application-mac.properties` 載入平台專屬的 stdio 啟動指令。若改成單純的 `SpringApplication.run(...)`，MCP 連線會靜默地 fallback 並無法運作 — 請勿重構掉這段邏輯。

### 三個 controller、三個獨立的 ChatMemory
`FileSystemMcpController`（`/api/filesystem/chat`）、`GithubMcpController`（`/api/github/chat`）和 `HelpDeskController`（`/api/helpdesk/chat`）各自持有 **自己的** `MessageWindowChatMemory` 欄位。即使都用 `username` 作為 `CONVERSATION_ID`，各 store 是實體分離的物件 — 不同功能的對話歷史不會互相滲入。加入新功能時請保留這個隔離。

各 controller 的工具指派也是固定的：
- `FileSystemMcpController` — 僅使用 `filesystem` 工具（在建構時快取）。
- `GithubMcpController` — 僅使用 `github` 工具（在建構時快取）。
- `HelpDeskController` — 僅使用 helpdesk 工具，透過 MCP server 自報的名稱 `mySpringAi_MCP_Server_stdio` 比對（這是 server 的 `spring.application.name`，**不是** client 端的 connection key `helpdesk-ticket-mcp-server-stdio`）。

### 工具選擇有兩層 — 別混淆
- **`McpServerToolFilter`**（`util/`）— 一個實作 `McpToolFilter` 的 Spring bean。全域、lazy、有快取。透過 `application.properties` 中的 `mcp.tool-filter.blocked-servers` 和 `mcp.tool-filter.blocked-tool-prefixes` 設定。只在 `McpToolsChangedEvent` 時重新執行。
- **`ToolUtil.selectToolsFor(mcpClients, serverHint, toolHint)`** — per-request 手動選取，對 server 名稱和 tool 名稱做模糊 `contains` 比對。這是每個 controller 挑選自己工具集的方式。用途：「這個端點只能看到這些工具」。此方法會 `log.info` 每個被開放的 `server='...' tool='...'`，並在最後印出「共開放 N 個 tools」摘要 — 啟動時可從 log 直接對照每個 controller 拿到哪些工具。

### Elicitation 流程（`HelpDeskElicitationProvider` + `ElicitationSessionStore` + `ElicitationSseService`）
這是整個 codebase 最微妙的一段 — MCP server 可以在 tool 執行中途暫停以向使用者索取更多資料，而使用者的回覆是透過 **另一個獨立的 HTTP request** 送達。兩條 thread 透過 `CompletableFuture` 協調：

1. 第一次 `POST /api/helpdesk/chat` → LLM 呼叫 `createTicket` → server 發出 `ElicitRequest`。
2. `@McpElicitation` handler 呼叫 `sessionStore.register(request, owner)`，將提示 + schema 推送到該 owner 的 SSE stream，然後 **阻塞於 `future.get(5min)`** — 此 Tomcat thread 進入 park 狀態。
3. 瀏覽器（`EventSource /api/helpdesk/elicitation/stream?username=...`）顯示提示。
4. 使用者在聊天框回覆 → 第二次 `POST /api/helpdesk/chat` 並帶上 `sessionId`。
5. `HelpDeskController.chat()` 偵測到 `sessionId` 便路由到私有的 `handleElicitationChatResponse`，用 `parserClient`（一個 **獨立** 的 `ChatClient`，不帶 MCP tools、advisors、記憶）把自然語言轉成 `Map`，再呼叫 `sessionStore.complete(sessionId, owner, data)`。
6. Future 完成 → 第一條 thread 被 unpark → 回傳 `ElicitResult.ACCEPT + data` → server 完成 tool → 第一次 POST 才真正回傳。

修改此段程式時務必守住的不變條件：
- `owner`（username）必須在 `ElicitRequest.meta()` 中；缺失即回傳 `DECLINE`。Session 操作（`complete`、`cancel`）皆會驗證 owner 以避免跨使用者誤取。
- `parserClient` **必須** 保持無工具狀態 — 若重用主要的 `chatClient`，解析步驟本身可能觸發巢狀 tool call。Spring AI 的 `ChatClient.Builder` 是 prototype scope，因此注入兩個 builder 參數會得到兩個互相獨立的 builder。
- Client 端 `spring.ai.mcp.client.request-timeout=300s` 與 server 端（在 JAR 啟動參數中的）`-Dspring.ai.mcp.server.request-timeout=300s` 必須都設為 5 分鐘，以涵蓋 elicitation 等待時間。`future.get(5, MINUTES)` 與此對齊。
- SSE 重連：`elicitationStream` 在使用者訂閱後會重送 `pendingForOwner(username)`，讓 elicitation 中途頁面重新整理時不會遺失提示。

### Sampling（`HelpDeskSamplingProvider`）
`@McpSampling` handler 注入 `ChatModel`，**不是** `ChatClient`。`ChatClient` 已經透過 `defaultTools()` 帶了 MCP tools，若在此使用會讓 sampled call 再次呼叫某個可能自己也會 sampling 的 tool → 無限迴圈。Sampling 一律使用原生的 `ChatModel`。

### Server → client 可觀測性
- `HelpDeskLogBridge`（`@McpLogging`）— server 的 `ctx.info(...)` 轉成 client 端的 SLF4J log。`clients = "helpdesk-ticket-mcp-server-stdio"` 對應 `application-{profile}.properties` 中的 **connection key**，不是 server 自報名稱。
- `HelpDeskToolProgressListener`（`@McpProgress`）— 只有 `HelpDeskController` 會在 `toolContext` 傳入 `Map.of("progressToken", UUID)`；server 帶著同一個 token 回報進度百分比。執行 thread 與被阻塞的 `.call().content()` 不同條。（filesystem / github controller 目前未傳 progressToken，若之後要接進度回報，記得也要加上。）

### Advisors chain（順序很重要）
`TokenUsageAuditAdvisor`（order = -1）包住 `PrettyLoggerAdvisor`（order = 0），後者再包住 `MessageChatMemoryAdvisor`。Token 稽核看得到整條 chain 的耗時；PrettyLogger 用外框格式輸出 request/response。每個 request 開始時會呼叫 `prettyLoggerAdvisor.reset()` 讓 `#N` 呼叫計數重新從 1 開始。

## 陷阱

- **Server 端 stdout 污染會弄壞 MCP。** Helpdesk server 是 Spring Boot — 預設的 logback 設定會把啟動 log 印到 stdout，這會污染 stdio channel 上的 JSON-RPC 傳輸。Server 端的 logback 必須改導向 `System.err`。若你動到 server，請驗證這點。
- **Windows 需要 `cmd /c` 包裹 `npx`**，但 `docker` 和 `java` 不需要。macOS 可直接執行 `npx`。這也是為什麼兩份 properties 檔要分開。
- **`spring-ai.version` 透過 `<properties>` 鎖在 2.0.0**，並透過 `spring-ai-bom` 從 `<dependencyManagement>` 匯入。升級 Spring AI 前務必檢查 MCP annotation 表面（`@McpElicitation`、`@McpSampling`、`@McpLogging`、`@McpProgress`）— 這些 API 在不同 milestone 間有變動。
- `h2db/` 底下的 h2 檔案 store 是由 **server** JAR 寫入，不是本 client。`.gitignore` 已涵蓋 `*.mv.db` 等，但別不小心把 dump 檔提交進來。

## 相關文件

Repository 慣例（commit 風格、測試門檻、格式）位於 `AGENTS.md`。第一次 PR 前請先讀過。
