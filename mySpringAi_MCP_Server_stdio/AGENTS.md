# Repository Guidelines

## 專案結構與模組組織

本儲存庫是一個以 Maven 建置的 Spring Boot MCP server。正式程式碼位於 `src/main/java/com/example/myspringai_mcp_server_stdio/`，並依職責分層：`tool/` 提供 MCP tools、`service/` 處理工單流程、`repo/` 負責 Spring Data 存取、`entity/` 定義持久化模型、`payload/` 放置請求 records，而 `config/` 負責初始化應用程式資料。執行設定集中在 `src/main/resources/application.properties`。測試位於 `src/test/java/`，並對應正式程式碼的 package 結構。`target/` 與 `h2db/` 分別存放建置產物及檔案型 H2 資料庫，請勿提交。

## 建置、測試與開發指令

請使用儲存庫內附的 Maven Wrapper，讓所有貢獻者使用一致的 Maven 設定：

- `.\mvnw.cmd clean test`：編譯專案並執行所有測試。
- `.\mvnw.cmd clean package`：建置可執行 JAR，輸出至 `target/`。
- `.\mvnw.cmd spring-boot:run`：啟動 stdio MCP server，供本機整合測試。
- `java -jar target\mySpringAi_MCP_Server_stdio-0.0.1-SNAPSHOT.jar`：執行已封裝的 server。

本專案需要 Java 25。MCP 訊息透過標準輸入與輸出傳輸，因此不要將 banner 或一般應用程式 log 寫入 stdout；現有設定已抑制這類輸出。

## 程式風格與命名慣例

遵循現有 Java 慣例：使用四個空白縮排，每個檔案只放一個 public type；type 使用 `PascalCase`，method 與 field 使用 `camelCase`，package 名稱使用小寫。依賴方向應維持 MCP tool → service → repository。小型且不可變的 payload 優先使用 record；Lombok 僅用於減少例行樣板程式碼。目前未設定 formatter 或 linter，提交前請比照鄰近程式碼並整理 imports。

## 測試準則

測試使用 JUnit 5 與 Spring Boot Test。測試類別命名為 `*Tests`，測試方法則描述可觀察行為，例如 `createsTicketWhenContactIsAccepted`。商業規則應加入聚焦的 service 測試；Spring wiring 則使用 context-loading 或整合測試。目前沒有強制覆蓋率門檻，但新增功能與錯誤修正仍應附上迴歸測試。建立 pull request 前請執行 `.\mvnw.cmd clean test`。

## Commit 與 Pull Request 準則

近期紀錄多使用簡短的 `update`，但新 commit 應採明確的祈使句，例如 `Add ticket priority validation`，且每次 commit 只處理一個主題。Pull request 應說明行為變更、列出驗證指令、連結相關 issue，並特別標示 MCP tool schema、H2 持久化或 `application.properties` 的變更。只有在影響 client UI 或 Inspector 輸出時才需附上截圖。

## 設定與資料安全

請勿提交憑證、本機資料庫檔案或機器專用路徑。由於本機 H2 資料庫會持久保存並在啟動時初始化，請審慎檢查 schema 與 `ddl-auto` 的異動。
