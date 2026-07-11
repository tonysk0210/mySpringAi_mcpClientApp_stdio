import { useRef, useState } from "react";
import { NavLink } from "react-router-dom";
import { useUsername } from "../context/UsernameContext";

export default function Navbar() {
  const { username, setUsername } = useUsername(); // 從 Context 取得 username 和 setUsername 函數
  const [editing, setEditing] = useState(!username); // 控制目前是「顯示按鈕」還是「顯示輸入框」
  const [draft, setDraft] = useState(username); // 輸入框的暫存值。使用者打字時改的是 draft，不是直接改 username，避免每打一個字就觸發全域重新渲染。按確認才真正寫入。
  const inputRef = useRef(null); // 用來取得輸入框的 ref

  // 開始編輯使用者名稱
  function startEdit() {
    setDraft(username); // 把目前已儲存的名稱填入草稿
    setEditing(true); // 切換為輸入框
    setTimeout(() => inputRef.current?.focus(), 0);
  }

  // 確認使用者名稱
  function confirm() {
    const name = draft.trim();
    setUsername(name); // 不管空或非空都寫回 context（空 = 清除）
    if (name) setEditing(false); // 有值才關閉 editing，空值強制繼續輸入
  }

  // 處理鍵盤事件
  function handleKeyDown(e) {
    if (e.key === "Enter") confirm();
    if (e.key === "Escape" && username) {
      setDraft(username);
      setEditing(false);
    }
  }

  return (
    <nav className="navbar">
      <span className="navbar-logo">MCP Client-App UI Chat</span>

      <div className="navbar-links">
        <NavLink
          to="/helpdesk"
          className={({ isActive }) =>
            isActive ? "nav-link active" : "nav-link"
          }
        >
          AI 智能工單系統 demo
        </NavLink>
        <NavLink
          to="/mcp-chat"
          className={({ isActive }) =>
            isActive ? "nav-link active" : "nav-link"
          }
        >
          外接 MCP 工具 demo
        </NavLink>
      </div>

      {/* 使用者名稱輸入 */}
      <div className="navbar-user">
        {editing ? (
          <input
            ref={inputRef}
            className="username-input"
            value={draft}
            placeholder="輸入使用者名稱"
            onChange={(e) => setDraft(e.target.value)} // 即時更新草稿
            onKeyDown={handleKeyDown} // 監聽鍵盤事件
            onBlur={confirm} // 失去焦點時確認
            autoFocus // 自動獲得焦點
          />
        ) : (
          <button
            className="username-btn"
            onClick={startEdit}
            title="點擊更改使用者名稱"
          >
            {username || "設定名稱"} ✎
          </button>
        )}
      </div>
    </nav>
  );
}
