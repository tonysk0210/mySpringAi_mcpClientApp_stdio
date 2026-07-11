import { useEffect, useRef, useState } from "react";

// ChatBox 是純展示元件，自己不碰 API，所有行為都從外部傳入：
export default function ChatBox({
  messages, // 所有對話紀錄 { role, content, variant } ; 陣列
  onSend, // 使用者送出訊息時，把文字交給父層處理 ; 函式
  onCancel, // elicitation 取消按鈕的回呼 ; 函式
  isLoading, // true → 顯示三點動畫泡泡 ; 布林值
  allowSendWhileLoading = false, // elicitation 中允許在 loading 時仍可送出 ; 布林值
  disabled = false, // 無 username 時鎖住整個輸入區 ; 布林值
  placeholder = "輸入訊息...", // 輸入框預設文字 ; 字串
}) {
  const [input, setInput] = useState(""); // 輸入框目前的文字
  const bottomRef = useRef(null); // 指向訊息列最底部的空 div（捲動用）
  const inputRef = useRef(null); // 指向 textarea（focus 用）

  // 每次 messages 或 isLoading 變化時，自動捲到最底部
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, isLoading]);

  // 解除鎖定時自動 focus 回輸入框
  useEffect(() => {
    if (!disabled && !isLoading) {
      inputRef.current?.focus();
    }
  }, [disabled, isLoading]);

  // Enter 鍵送出，Shift+Enter 換行
  function handleKeyDown(e) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      submit();
    }
  }

  // 判斷輸入框是否被鎖定（disabled 或 isLoading 且不允許送訊）
  const inputLocked = disabled || (isLoading && !allowSendWhileLoading);

  function submit() {
    const text = input.trim();
    if (!text || inputLocked) return;
    setInput("");
    onSend(text); // 把文字交給父層（打 API）
    inputRef.current?.focus(); // 送完後自動 focus 回輸入框
  }

  return (
    <div className="chatbox">
      <div className="chatbox-messages">
        {/* 訊息列表 */}
        {messages.map((msg, i) => (
          <div key={i} className={`bubble-row ${msg.role}`}>
            <div className="bubble">
              <span className="bubble-role">
                {msg.role === "user" ? "您" : "AI 人工客服"}
              </span>
              <p className={msg.variant ? `msg-${msg.variant}` : ""}>
                {msg.content}
              </p>
            </div>
          </div>
        ))}
        {/* 載入中動畫 */}
        {isLoading && (
          <div className="bubble-row assistant">
            <div className="bubble loading">
              <span className="bubble-role">AI 人工客服 正在回覆中...</span>
              <span className="dots">
                <span />
                <span />
                <span />
              </span>
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
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
        />
        {/* allowSendWhileLoading 是 true 而且 onCancel 有傳進來才顯示取消按鈕 */}
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
  );
}
