import { useState } from 'react'
import ChatBox from '../components/ChatBox'
import { useUsername } from '../context/UsernameContext'

export default function McpChatPage() {
  const { username } = useUsername()
  const [messages, setMessages] = useState([])
  const [isLoading, setIsLoading] = useState(false)

  async function handleSend(text) {
    if (!username) return
    setMessages(prev => [...prev, { role: 'user', content: text }])
    setIsLoading(true)

    try {
      const res = await fetch('/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', username },
        body: JSON.stringify({ message: text }),
      })
      const rawText = await res.text()
      console.log('[MCP Chat] status:', res.status, '| reply:', rawText)

      if (!res.ok) {
        setMessages(prev => [...prev, { role: 'assistant', content: `❌ 伺服器錯誤 (${res.status})：${rawText || '無詳細資訊'}` }])
        return
      }

      const reply = rawText.trim() || '（伺服器回傳空回應，請確認 Spring AI 設定與 OpenAI API Key）'
      setMessages(prev => [...prev, { role: 'assistant', content: reply }])
    } catch (e) {
      console.error('[MCP Chat] fetch error:', e)
      setMessages(prev => [...prev, { role: 'assistant', content: '❌ 連線錯誤，請確認後端服務是否啟動。' }])
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <h2>MCP Chat</h2>
        <p>透過 AI 操作檔案系統與 GitHub 工具。</p>
      </div>
      {!username && (
        <div className="page-warning">請先在右上角設定使用者名稱才能傳送訊息。</div>
      )}
      <ChatBox
        messages={messages}
        onSend={handleSend}
        isLoading={isLoading}
        disabled={!username}
        placeholder="輸入指令，例如：列出 /tmp 下的檔案..."
      />
    </div>
  )
}
