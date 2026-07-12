# Repository Guidelines

## 專案結構與模組組織

此 repository 是 MCP UI 的 Vite React 前端。應用程式碼位於 `src/`：共用 API 設定在 `src/api/client.js`，可重用 UI 在 `src/components/`，使用者名稱狀態在 `src/context/`，路由層級畫面在 `src/pages/`。全域樣式分散在 `src/App.css` 與 `src/index.css`。靜態瀏覽器資產放在 `public/`；`dist/` 是 Vite 產生的輸出，不要直接編輯。

## 建置、測試與開發指令

- `npm install`：依照 `package-lock.json` 安裝相依套件。
- `npm run dev`：啟動 Vite dev server，並將 `/api` proxy 到 `http://localhost:8080`。
- `npm run build`：在 `dist/` 建立 production bundle。
- `npm run preview`：在本機啟動 production build 以便驗證。
- `npm run lint`：對 JavaScript 與 JSX 檔案執行 ESLint。

## 程式風格與命名慣例

使用 React function components 與 hooks。Component 與 page 檔案使用 `PascalCase` 命名，例如 `Navbar.jsx` 或 `HelpdeskChatPage.jsx`；區域變數、handler 與 helper function 使用 `camelCase`。JSX 維持兩個空白縮排，優先撰寫小型、路由專屬的 component，避免過早建立大型跨功能抽象。遵循附近檔案既有的 import 風格，提交前先執行 `npm run lint`。

## 測試指南

目前 `package.json` 尚未設定自動化 test script 或測試框架。現階段請使用 `npm run lint`、`npm run build`，並在 Vite dev server 中手動驗證變更。新增測試時，請將測試放在受影響功能附近，或使用命名清楚的測試目錄；在文件中要求測試前，先新增對應的 `npm test` script。

## Commit 與 Pull Request 指南

近期 commit message 短且偏非正式，因此目前尚無嚴格專案慣例。請使用簡潔、祈使語氣的訊息描述變更範圍，例如 `Update helpdesk chat cancellation UI`。Pull request 應包含簡短摘要、手動驗證步驟；若有相關 issue 或任務背景請一併連結，畫面可見的 UI 變更則附上截圖。

## 安全性與設定提示

前端透過 `vite.config.js` 中設定的 `/api` proxy 呼叫後端 API。不要在 React source 中 hard-code secret 或 production credential。後端 URL 變更應集中在 Vite config 或 API client，而不是把 endpoint 字串分散在各個 page。
