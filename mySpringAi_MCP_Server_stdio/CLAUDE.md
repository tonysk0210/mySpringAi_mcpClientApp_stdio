# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 專案概述

本專案是一個使用 **stdio** transport 的 Spring AI **MCP (Model Context Protocol) Server**。MCP client（例如 Claude Desktop、MCP Inspector）會將此 JAR 作為子行程啟動，並透過 stdin/stdout 以 JSON-RPC 進行通訊。此 server 對外提供 IT 服務台工單（Help Desk Ticket）相關的 MCP tools，資料儲存於 H2 檔案資料庫。

- Spring Boot **4.1.0**、Spring AI **2.0.0**（`spring-ai-starter-mcp-server`）
- Java **25**、Lombok、JPA/Hibernate、H2 file DB
- 程式碼註解與使用者可見的字串皆為 **繁體中文**

## 常用指令

打包、以 MCP Inspector 測試（參考 `ReadMe.txt`）：

```
mvn clean install -DskipTests           # 打包 jar 到 target/ 並安裝到本機 ~/.m2
mvn clean package -DskipTests           # 只打包 jar 到 target/
npx @modelcontextprotocol/inspector     # 啟動 MCP Inspector UI 以測試 tools
java -jar target/mySpringAi_MCP_Server_stdio-0.0.1-SNAPSHOT.jar
```

測試：

```
mvn test                                # 執行全部測試
mvn -Dtest=MySpringAiMcpServerStdioApplicationTests test   # 執行單一測試
```

## stdio 模式的關鍵限制

`application.properties` 已將此 app 設定為專門讓 stdin/stdout 承載 MCP JSON-RPC 通道：

- `spring.main.web-application-type=none` — 不啟動 embedded Tomcat；請勿加入 web 相關程式碼。
- `logging.level.root=error` 與 `spring.main.banner-mode=off` — 任何多餘的 stdout 輸出都會破壞 MCP 協定。**絕對不要**使用 `System.out.println`，也不要把 root log level 調低。使用者可見的 log 與進度請透過 tool 方法中的 `ctx.log(...)` / `ctx.progress(...)` 發送（範例可參考 `HelpDeskTicketTool.info(...)` helper 方法）。

## 架構

單一 package 的分層架構，位於 `com.example.myspringai_mcp_server_stdio`：

- `tool/HelpDeskTicketTool` — **唯一的 MCP 對外接口**。標註 `@McpTool` 的方法會被 Spring AI 自動註冊。這些方法**刻意宣告為 package-private**，因為它們是由 framework 透過 reflection 呼叫，並不是要給其他 Java 程式碼直接呼叫。
- `service/HelpDeskTicketService` → `repo/HelpDeskTicketRepository`（Spring Data JPA）→ `entity/HelpDeskTicketEntity`（對應 H2 資料表 `HELP_DESK_TICKETS`）。
- `payload/` — 作為 tool 輸入 DTO 的 record。其中 `TicketContactInfo` 特別重要：Spring AI 會從此 record 的欄位結構自動產生 **MCP elicitation 的 `requestedSchema`**，所以更改欄位名稱／結構會直接改變 client 端向使用者詢問的問卷內容。
- `config/DataInitializer` — 實作 `CommandLineRunner`，首次啟動時 seed 約 9 筆狀態為 CLOSED 的工單，作為 `troubleshootIssue` 使用的知識庫。以 `findByStatus("CLOSED").isEmpty()` 判斷是否已 seed 過。

### 三個示範用的 MCP 能力

每個 `@McpTool` 方法都會收到一個 `McpSyncRequestContext ctx`（每次請求都會新建一個，不是 singleton — 概念類似 `HttpServletRequest`）。三個 tool 分別示範一項 MCP 能力：

1. **`createTicket` — Elicitation**：呼叫 `ctx.elicit(...)` 暫停執行，由 client 端向使用者收集 `priority` 與 `contactPhone`。使用前一律以 `ctx.elicitEnabled()` 判斷；若 client 不支援或使用者取消，會退回預設值（`MEDIUM`、`N/A`）。
2. **`getTicketStatus` — Progress notifications**：在迴圈中持續呼叫 `ctx.progress(...)` 發送進度事件，讓支援進度 UI 的 MCP client 可以顯示 progress bar。
3. **`troubleshootIssue` — Sampling**：呼叫 `ctx.sample(...)` 借用 **client 端** 的 LLM。Server 準備好 system prompt 與歷史已解決工單作為知識庫，再請 client LLM 產出自助排障建議。使用前以 `ctx.sampleEnabled()` 判斷。

新增 tool 時請沿用相同模式：先以對應的 `*Enabled()` 檢查該 MCP 能力是否可用、提供合理的 fallback，並同時使用 `ctx.log(...)` 與 `log.info(...)` 記錄（`HelpDeskTicketTool` 底部的 `info(ctx, ...)` helper 會以 INFO 等級將訊息送到 MCP client）。

## 資料庫

H2 檔案資料庫位於 `./h2db/mcpserver_stdio`，設定 `AUTO_SERVER=true`，讓執行中的 app、H2 Console 以及外部工具（例如 DataGrip）可同時存取同一個檔案。`ddl-auto=update` 適合本地開發，正式環境請勿以此取代 migration 流程。此資料庫目錄本身**不在** `.gitignore` 內，僅 `*.mv.db` / `*.lock.db` / `*.trace.db` 檔案被忽略。
