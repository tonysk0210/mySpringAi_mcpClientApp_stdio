# Repository Guidelines

## 專案結構與模組配置

本專案是 Java 25、Spring Boot 4.1 與 Spring AI 2.0 的 Maven 應用程式。正式程式位於 `src/main/java/com/example/myspringai_mcp_client/`：`controller/` 提供一般 MCP 與 Helpdesk HTTP/SSE 端點，`advisor/` 處理模型呼叫的日誌與用量稽核，`util/` 封裝工具篩選、elicitation、sampling 與進度事件，`payload/` 放置 API 資料物件。執行設定位於 `src/main/resources/`，並以 `application-windows.properties`、`application-mac.properties` 及對應的 `mcp-servers-*.json` 區分平台。測試依照正式套件結構放在 `src/test/java/`。`target/`、`h2db/` 與本機 IDE 檔案均不應提交。

## 建置、測試與本機執行

- `mvn clean test`：清除建置輸出並執行完整測試套件。
- `mvn package`：建立 `target/mySpringAi_MCP_Client-0.0.1-SNAPSHOT.jar`。
- `mvn spring-boot:run -Dspring-boot.run.profiles=windows`：以 Windows MCP 設定啟動應用程式；macOS 請改用 `mac`。
- `mvn -Dtest=ElicitationSessionStoreTest test`：快速執行單一測試類別。

專案啟動前需準備 Java 25、Maven 3.9，以及設定中實際使用的 `npx`、Docker 或 Helpdesk server JAR。

## 程式風格與命名慣例

Java 使用 4 個空白縮排、同一行左大括號，套件名稱全小寫。類別採 `PascalCase`，方法與欄位採 `camelCase`，常數採 `UPPER_SNAKE_CASE`。Controller 保持端點協調職責，共用 MCP 流程放入 `util/`；優先使用 constructor injection。專案目前未設定獨立 formatter 或 linter，提交前請沿用鄰近程式碼的 import 分組、Javadoc 與繁體中文使用者訊息風格。

## Agent 操作偏好

回覆使用繁體中文。進行 CLI 或程式碼搜尋時，先判斷最小有效搜尋範圍，並優先使用 `rg`；若 `rg` 不可用，再改用 PowerShell 的 `Get-ChildItem`、`Select-String` 或 `Get-Content`。搜尋時先限制目錄、檔名或副檔名，避免不必要的全域掃描。

使用者詢問 library、framework、SDK、API、CLI tool 或 cloud service 的最新用法、設定、版本遷移或除錯時，使用 Context7。先以官方名稱與完整問題執行 `npx ctx7@latest library <name> "<question>"`，依 exact match、描述、snippet 數、來源可信度與 benchmark 分數選出 `/org/project` ID；除非使用者已提供有效 ID，否則不得跳過 library 查詢。接著執行 `npx ctx7@latest docs <libraryId> "<question>"` 取得文件後回答；跨多個概念時分開查 docs，但每題最多 3 個 Context7 指令。Context7 指令需在 Codex 預設 sandbox 外執行；若遇到 DNS、host resolution 或 fetch failed，改在 sandbox 外重跑。若遇到 quota 錯誤，請告知需 `npx ctx7@latest login` 或設定 `CONTEXT7_API_KEY`。重構、商業邏輯除錯、code review 或一般程式概念不需使用 Context7。

## 測試規範

測試使用 JUnit 5、Mockito 與 Spring Boot test starter。測試類別命名為 `<ClassName>Test`，方法名稱應描述行為與預期，例如 `cancelRemovesSessionAndCancelsWaitingFuture`。修改 session ownership、tool selection、elicitation 或 controller 狀態碼時，須加入正常與拒絕／不存在案例。專案目前沒有硬性覆蓋率門檻；每次 PR 至少應通過 `mvn clean test`。

## Commit 與 Pull Request

近期歷史多為簡短的 `update`，尚無正式提交格式。新提交請改用明確、祈使語氣的摘要，例如 `Add elicitation cancellation test`，並將不相關變更拆開。PR 應說明目的、主要行為差異、測試結果及任何設定影響；若 API、SSE 事件或前端可見行為改變，附上範例 request/response 或畫面截圖，並連結相關 issue。

## 安全與設定

不得把 `OPENAI_API_KEY`、GitHub token、個人路徑或產生的資料庫檔提交至版本庫。密鑰一律透過環境變數提供。調整 `application-*.properties` 或 `mcp-servers-*.json` 時，請同步檢查 Windows/macOS 差異，並避免在日誌輸出憑證或完整敏感 payload。
