# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 常用指令

- `npm run dev` — 啟動 Vite 開發伺服器（會將 `/api` 代理到 `http://localhost:8080`）
- `npm run build` — 產生 production build 到 `dist/`
- `npm run preview` — 預覽已建置的 bundle
- `npm run lint` — 對整個專案跑 ESLint

專案未設定測試工具。

## 後端依賴

本專案是 Spring AI MCP client 專案的**前端**部分，預期後端 Spring Boot 服務跑在 `http://localhost:8080`，並提供以下 API：

- `POST /api/helpdesk/chat`（body `{ message, sessionId }`，header `username`）
- `GET  /api/helpdesk/elicitation/stream?username=...` — **SSE** 端點，後端會推送 `elicitation` 事件
- `POST /api/helpdesk/elicitation/:sessionId/cancel`（header `username`）
- `POST /api/filesystem/chat`（body `{ message }`，header `username`）
- `POST /api/github/chat`（body `{ message }`，header `username`）

所有 API 呼叫都透過 `src/api/client.js`（axios，`baseURL: /api`）。後端以 `username` header 辨識使用者，該值來自 `UsernameContext`（持久化在 `localStorage` 的 `mcp-username` key）。

## 架構

三個 demo 頁面共用一個 `ChatBox`，各自對應不同的後端契約：

- **`HelpdeskChatPage`** 是最複雜的一頁。它會維持一條以 `username` 為 key 的長效 SSE 連線，讓後端可以主動推送 **elicitation** 請求（在 MCP 工具呼叫過程中「請補充某項資訊」的追問）。收到 elicitation 事件時：
  1. 記下 `sessionId`，進入 `isEliciting` 狀態（即使 `isLoading` 為 true，輸入框仍可使用 — 因為原本的 POST 還沒回來）；
  2. 使用者的下一則訊息會帶著這個 `sessionId` POST 出去，讓後端能把補充資料交給正在等待的那次工具呼叫；
  3. 或使用者按取消 → POST 到 `/helpdesk/elicitation/:sessionId/cancel`。

  SSE 斷線時會以 2 秒後重試自動重連。`seenElicitationSessionsRef` 這個 Set 用來在重連後過濾掉同一個 `sessionId` 的重複 prompt。頁首的 `SseStatusBadge` 會顯示 `idle | connecting | connected | disconnected`，並用來決定 `ChatBox` 的 `disabled` 是否開啟。

- **`FileSystemChatPage`** 與 **`GithubChatPage`** 為單純的 request/response，沒有 SSE，也沒有 elicitation 流程。主要是為了展示其他 MCP server。

- **`ChatBox`** 為純展示元件，只保管 textarea 的本地輸入狀態；messages、loading、cancel、disabled 全部由父層傳入。`allowSendWhileLoading` prop 就是讓 Helpdesk 頁在 elicitation 時仍能送出訊息的關鍵。

- **`UsernameContext`** 為全域 context，三個頁面皆會讀取，Navbar 負責寫入。變更 username 會拆掉並重建 Helpdesk 的 SSE 連線（也就是 SSE `useEffect` 中把 `username` 放進 dependency array 的原因）。

## 慣例

- 現有元件的註解以**繁體中文**為主，且 useEffect 內常見以 `### N.` 標記的分步註解。編輯現有檔案時請沿用同樣風格；新檔案可以精簡一點，除非該檔本身就註解密集。
- 使用者可見的文字皆為繁體中文。
- 樣式為純 CSS，寫在 `src/App.css` 與 `src/index.css` — 沒有 CSS Modules，也沒有 Tailwind。
- 技術棧為 React 19 + Vite 8，使用 JS（非 TypeScript），ESLint 採 flat config，帶 `react-hooks` 與 `react-refresh` plugin。
