import { useRef, useState } from 'react'
import { NavLink } from 'react-router-dom'
import { useUsername } from '../context/UsernameContext'

export default function Navbar() {
  const { username, setUsername } = useUsername()
  const [editing, setEditing] = useState(!username)
  const [draft, setDraft] = useState(username)
  const inputRef = useRef(null)

  function startEdit() {
    setDraft(username)
    setEditing(true)
    setTimeout(() => inputRef.current?.focus(), 0)
  }

  function confirm() {
    const name = draft.trim()
    setUsername(name)       // 不管空或非空都寫回 context（空 = 清除）
    if (name) setEditing(false)  // 有值才關閉 editing，空值強制繼續輸入
  }

  function handleKeyDown(e) {
    if (e.key === 'Enter') confirm()
    if (e.key === 'Escape' && username) {
      setDraft(username)    // 取消編輯，還原 draft
      setEditing(false)
    }
  }

  return (
    <nav className="navbar">
      <span className="navbar-logo">MCP UI</span>

      <div className="navbar-links">
        <NavLink to="/helpdesk" className={({ isActive }) => isActive ? 'nav-link active' : 'nav-link'}>
          IT Helpdesk
        </NavLink>
        <NavLink to="/mcp-chat" className={({ isActive }) => isActive ? 'nav-link active' : 'nav-link'}>
          MCP Chat
        </NavLink>
      </div>

      <div className="navbar-user">
        {editing ? (
          <input
            ref={inputRef}
            className="username-input"
            value={draft}
            placeholder="輸入使用者名稱"
            onChange={e => setDraft(e.target.value)}
            onKeyDown={handleKeyDown}
            onBlur={confirm}
            autoFocus
          />
        ) : (
          <button className="username-btn" onClick={startEdit} title="點擊更改使用者名稱">
            {username || '設定名稱'} ✎
          </button>
        )}
      </div>
    </nav>
  )
}
