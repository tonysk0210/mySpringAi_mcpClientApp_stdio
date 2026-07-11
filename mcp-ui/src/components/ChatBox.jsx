import { useEffect, useRef, useState } from "react";

export default function ChatBox({
  messages,
  onSend,
  isLoading,
  allowSendWhileLoading = false,
  disabled = false,
  placeholder = "輸入訊息...",
}) {
  const [input, setInput] = useState("");
  const bottomRef = useRef(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, isLoading]);

  function handleKeyDown(e) {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      submit();
    }
  }

  const inputLocked = disabled || (isLoading && !allowSendWhileLoading);

  function submit() {
    const text = input.trim();
    if (!text || inputLocked) return;
    setInput("");
    onSend(text);
  }

  return (
    <div className="chatbox">
      <div className="chatbox-messages">
        {messages.map((msg, i) => (
          <div key={i} className={`bubble-row ${msg.role}`}>
            <div className="bubble">
              <span className="bubble-role">
                {msg.role === "user" ? "你" : "AI"}
              </span>
              <p>{msg.content}</p>
            </div>
          </div>
        ))}
        {isLoading && (
          <div className="bubble-row assistant">
            <div className="bubble loading">
              <span className="bubble-role">AI</span>
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
          className="chatbox-input"
          rows={1}
          value={input}
          placeholder={placeholder}
          disabled={inputLocked}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
        />
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
