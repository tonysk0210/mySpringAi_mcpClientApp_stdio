# Repository Guidelines

## 專案結構與模組組織

此 repository 是三個本機專案的父層 workspace：

- `mySpringAi_MCP_Client/`：Spring Boot MCP client。Java 原始碼在 `src/main/java`，測試在 `src/test/java`，profile 設定在 `src/main/resources/application*.properties`。
- `mySpringAi_MCP_Server_stdio/`：使用 stdio transport 的 Spring Boot MCP server。領域程式碼分布在 `tool/`、`service/`、`repo/`、`entity/`、`payload/`。
- `mcp-ui/`：Vite React UI。主要程式碼在 `src/`，共用資產在 `public/`，產生的 build output 放在 `dist/`。

不要編輯 `node_modules/`、`dist/` 或 Maven `target/` output。模組專屬變更應留在真正擁有該行為的模組內。

## 建置、測試與開發指令

請在對應模組目錄執行指令：

- `.\mvnw.cmd test`：執行任一 Java 模組的 Spring Boot tests。
- `.\mvnw.cmd spring-boot:run`：在本機啟動 Spring Boot 模組。
- `npm install`：在 `mcp-ui/` 安裝 UI dependencies。
- `npm run dev`：啟動 Vite development server。
- `npm run build`：在 `dist/` 建立 production UI bundle。
- `npm run lint`：對 UI JavaScript 與 JSX 執行 ESLint。

## 程式風格與命名慣例

Java package 使用 `com.example.myspringai_mcp_*` 底下的命名；class 維持 PascalCase，method 與 field 維持 camelCase。請遵守既有 controller、advisor、utility、payload、service package 邊界。React component 使用 PascalCase 檔名，例如 `ChatBox.jsx`；hooks、helpers、API modules 使用 camelCase exports。UI 檔案偏好 2-space indentation，Java 則沿用 Maven 模組內既有格式。

## 測試準則

Java tests 使用 Spring Boot test starters，放在各模組的 `src/test/java`。新測試請以被測單元命名，例如 `HelpDeskControllerTest`。修改 controller contract、elicitation/session state 或 tool behavior 時，請加入聚焦測試。UI 目前有 lint/build checks，但沒有專用 test runner，因此 UI 變更請用 `npm run lint` 與 `npm run build` 驗證。

## Commit 與 Pull Request 準則

近期歷史使用 `update`、`css fix` 這類短訊息；建議改用更清楚的祈使句 subject，例如 `Fix helpdesk chat cancellation`。可行時讓 commit 聚焦單一模組。Pull request 應描述使用者可見變更、列出已執行指令、標明受影響模組；UI 變更請附 screenshot。

## 安全性與設定提示

不要 commit API keys 或本機 credentials。環境專屬值請放在既有 `application-windows.properties`、`application-mac.properties`，或本機 shell environment。變更 MCP wiring 時，請記錄該行為屬於 client、server 或 UI。
