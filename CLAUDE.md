# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository 佈局

本 repo 是一個由三個緊密相關子專案組成的 monorepo，展示 Spring AI **MCP over stdio** 的 end-to-end 樣貌。每個子目錄各有自己更詳細的 `CLAUDE.md`，請依據要動的部分先讀對應那份：

| 目錄 | 角色 | 技術棧 | 詳細指引 |
| --- | --- | --- | --- |
| `mySpringAi_MCP_Server_stdio/` | MCP **server**（Help Desk 工單，示範 elicitation / progress / sampling 三種 MCP 能力）| Spring Boot 4.1, Spring AI 2.0, Java 25, H2 file DB | [mySpringAi_MCP_Server_stdio/CLAUDE.md](mySpringAi_MCP_Server_stdio/CLAUDE.md) |
| `mySpringAi_MCP_Client/` | MCP **client** + REST/SSE Backend（連接 filesystem / github / helpdesk 三個 MCP servers，包裝為 HTTP 端點）| Spring Boot 4.1, Spring AI 2.0, Java 25, OpenAI `gpt-4o-mini` | [mySpringAi_MCP_Client/CLAUDE.md](mySpringAi_MCP_Client/CLAUDE.md) |
| `mcp-ui/` | 前端 SPA（三個聊天頁：Helpdesk / FileSystem / GitHub）| React 19, Vite 8, plain CSS | [mcp-ui/CLAUDE.md](mcp-ui/CLAUDE.md) |

## Server → Client 是「JAR 拷貝」而非 Maven 依賴

Client 透過 stdio 啟動 helpdesk server，做法是把 server 打包好的 JAR **實體檔案**放在 `mySpringAi_MCP_Client/mcp-server-stdio/mySpringAi_MCP_Server_stdio-0.0.1-SNAPSHOT.jar`，並於 `application-{windows,mac}.properties` 中以 `java -jar` 指令啟動它。**兩者不是 Maven 模組依賴，也沒有 parent pom**。因此 server 端變更後，必須手動重打並覆蓋這份 JAR，client 才會看到最新的 tool schema/行為：

```powershell
# 從 repo 根目錄
cd mySpringAi_MCP_Server_stdio ; .\mvnw.cmd clean package -DskipTests
Copy-Item target\mySpringAi_MCP_Server_stdio-0.0.1-SNAPSHOT.jar ..\mySpringAi_MCP_Client\mcp-server-stdio\ -Force
```

其他兩個 MCP servers（`filesystem`、`github`）分別由 `npx` 與 `docker` 啟動，配置也在 `application-{windows,mac}.properties`。

## 全端本機啟動

需要先設定 `OPENAI_API_KEY` 與 `GITHUB_PERSONAL_ACCESS_TOKEN`（Windows 用 `$env:`，mac 用 `export`）。三個 process 開三個 terminal，順序不強制但通常這樣起：

1. **Client（含內建 stdio 子行程 = helpdesk server）** — `cd mySpringAi_MCP_Client ; .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=windows"`（mac 用 `./mvnw` 與 `mac` profile）。Client 監聽 `:8080`。
2. **Frontend** — `cd mcp-ui ; npm run dev`。Vite 監聽 `:5173`，並將 `/api/**` proxy 到 `http://localhost:8080`。
3. Helpdesk server 由 client 以 stdio 子行程方式自動拉起，**不需要**單獨啟動；只有在單獨用 MCP Inspector 測 server tool 時才會 `java -jar ...` 直接執行。

## 跨專案的一致慣例

以下規則對三個子專案**都**適用；子專案 `CLAUDE.md` 中不會再重複：

- **語言**：程式碼註解、log 訊息、使用者可見字串一律**繁體中文**。回覆使用者也用繁體中文。
- **Spring 版本一致性**：三個 pom（client、server）都鎖定 Spring Boot 4.1.0 + Spring AI 2.0.0。若要升級 Spring AI，兩邊必須一起動 — `@McpElicitation` / `@McpSampling` / `@McpLogging` / `@McpProgress` 在不同 milestone 間 API 曾變動。
- **Windows 為主平台，macOS 兼容**：所有指令與檔案路徑優先以 Windows PowerShell 為主（`.\mvnw.cmd`、`$env:VAR`）。macOS 的替代做法在各專案 CLAUDE.md 有註明。
- **stdio 通道潔淨**：MCP over stdio 靠 stdin/stdout 承載 JSON-RPC。**任何** 額外的 `System.out.println` 或 log 到 stdout 都會弄壞協定。這是 server 側的鐵律，但 client 側若之後也做成 stdio server 需注意同一點。
- **敏感資料**：`OPENAI_API_KEY`、`GITHUB_PERSONAL_ACCESS_TOKEN`、H2 dump 檔（`*.mv.db`）皆不進版本控制。`.gitignore` 已涵蓋，但 `h2db/` 目錄本身仍在 repo 內，只有內容檔被忽略。

## `AGENTS.md` 是另一套獨立指引

每個子專案除了 `CLAUDE.md` 外，也各自有 `AGENTS.md`（Codex 用），內容重疊但不完全相同 — commit 訊息風格、測試門檻、格式等 repo 慣例寫在 `AGENTS.md`。首次要送 PR 前建議也讀過對應那份。
