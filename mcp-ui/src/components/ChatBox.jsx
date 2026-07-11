import { useEffect, useRef, useState } from 'react'

export default function ChatBox({
  messages,
  onSend,
  onCancel,
  isLoading,
  allowSendWhileLoading = false,
  disabled = false,
  placeholder = '輸入訊息...',
}) {
  const [input, setInput] = useState('')
  const bottomRef = useRef(null)
  const inputRef = useRef(null)

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, isLoading])

  // 解除鎖定時自動 focus 回輸入框
  useEffect(() => {
    if (!disabled && !isLoading) {
      inputRef.current?.focus()
    }
  }, [disabled, isLoading])

  function handleKeyDown(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      submit()
    }
  }

  const inputLocked = disabled || (isLoading && !allowSendWhileLoading)

  function submit() {
    const text = input.trim()
    if (!text || inputLocked) return
    setInput('')
    onSend(text)
    inputRef.current?.focus()
  }

  return (
    <div className="chatbox">
      <div className="chatbox-messages">
        {messages.map((msg, i) => (
          <div key={i} className={`bubble-row ${msg.role}`}>
            <div className="bubble">
              <span className="bubble-role">{msg.role === 'user' ? '你' : 'AI'}</span>
              <p className={msg.bold ? 'msg-bold' : ''}>{msg.content}</p>
            </div>
          </div>
        ))}
        {isLoading && (
          <div className="bubble-row assistant">
            <div className="bubble loading">
              <span className="bubble-role">AI</span>
              <span className="dots"><span /><span /><span /></span>
            </div>
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      <div className="chatbox-input-row">
        <textarea
          ref={inputRef}
          className="chatbox-input"
          rows={1}
          value={input}
          placeholder={placeholder}
          disabled={inputLocked}
          onChange={e => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
        />
        {allowSendWhileLoading && onCancel && (
          <button className="chatbox-cancel" onClick={onCancel}>
            取消
          </button>
        )}
        <button
          className="chatbox-send"
          onClick={submit}
          disabled={inputLocked || !input.trim()}
        >
          送出
        </button>
      </div>
    </div>
  )
}
